package com.newcodes7.small_town.corporation.service;

import static org.assertj.core.api.Assertions.*;

import com.newcodes7.small_town.config.IntegrationTestBase;
import com.newcodes7.small_town.corporation.dto.CorporationCreateDto;
import com.newcodes7.small_town.corporation.dto.CorporationResponseDto;
import com.newcodes7.small_town.corporation.dto.CorporationUpdateDto;
import com.newcodes7.small_town.corporation.entity.Industry;
import com.newcodes7.small_town.corporation.exception.CorporationNotFoundException;
import com.newcodes7.small_town.corporation.exception.DuplicateCorporationNameException;
import com.newcodes7.small_town.corporation.exception.InvalidParameterException;
import com.newcodes7.small_town.corporation.repository.CorporationRepository;
import com.newcodes7.small_town.corporation.repository.IndustryRepository;
import com.newcodes7.small_town.global.entity.Corporation;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

public class CorporationServiceTest extends IntegrationTestBase {

    @Autowired
    private CorporationService corporationService;

    @Autowired
    private CorporationRepository corporationRepository;

    @Autowired
    private IndustryRepository industryRepository;

    private Industry testIndustry;

    @BeforeEach
    void setUp() {
        // 테스트용 Industry 생성
        testIndustry = Industry.builder()
                .name("IT/소프트웨어")
                .build();
        testIndustry = industryRepository.save(testIndustry);
    }

    @Test
    @DisplayName("기업 생성 - 성공")
    void createCorporation_Success() {
        // given
        CorporationCreateDto dto = new CorporationCreateDto();
        dto.setName("테스트기업");
        dto.setAlternateName("Test Corp");
        dto.setIsDomestic(1);
        dto.setHomeLink("https://test.com");
        dto.setBlogLink("https://test.com/blog");
        dto.setBlogType("medium");

        // when
        CorporationResponseDto result = corporationService.createCorporation(dto);

        // then
        assertThat(result).isNotNull();
        assertThat(result.getId()).isNotNull();
        assertThat(result.getName()).isEqualTo("테스트기업");
        assertThat(result.getAlternateName()).isEqualTo("Test Corp");
        assertThat(result.getIsDomestic()).isTrue();
    }

    @Test
    @DisplayName("기업 생성 - 중복된 이름")
    void createCorporation_DuplicateName() {
        // given
        Corporation existing = Corporation.builder()
                .name("중복기업")
                .isDomestic(1)
                .build();
        corporationRepository.save(existing);

        CorporationCreateDto dto = new CorporationCreateDto();
        dto.setName("중복기업");
        dto.setIsDomestic(1);

        // when & then
        assertThatThrownBy(() -> corporationService.createCorporation(dto))
                .isInstanceOf(DuplicateCorporationNameException.class);
    }

    @Test
    @DisplayName("기업 생성 - null DTO")
    void createCorporation_NullDto() {
        // when & then
        assertThatThrownBy(() -> corporationService.createCorporation(null))
                .isInstanceOf(InvalidParameterException.class);
    }

    @Test
    @DisplayName("기업 생성 - 빈 이름")
    void createCorporation_EmptyName() {
        // given
        CorporationCreateDto dto = new CorporationCreateDto();
        dto.setName("");
        dto.setIsDomestic(1);

        // when & then
        assertThatThrownBy(() -> corporationService.createCorporation(dto))
                .isInstanceOf(InvalidParameterException.class);
    }

    @Test
    @DisplayName("기업 생성 - 업종 포함")
    void createCorporation_WithIndustries() {
        // given
        CorporationCreateDto dto = new CorporationCreateDto();
        dto.setName("업종포함기업");
        dto.setIsDomestic(1);
        dto.setIndustryIds(List.of(testIndustry.getId()));

        // when
        CorporationResponseDto result = corporationService.createCorporation(dto);

        // then
        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("업종포함기업");
    }

    @Test
    @DisplayName("기업 조회 - ID로 조회 성공")
    void getCorporationById_Success() {
        // given
        Corporation corporation = Corporation.builder()
                .name("조회기업")
                .isDomestic(1)
                .build();
        corporation = corporationRepository.save(corporation);

        // when
        CorporationResponseDto result = corporationService.getCorporationById(corporation.getId());

        // then
        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("조회기업");
    }

    @Test
    @DisplayName("기업 조회 - 존재하지 않는 ID")
    void getCorporationById_NotFound() {
        // when & then
        assertThatThrownBy(() -> corporationService.getCorporationById(999999L))
                .isInstanceOf(CorporationNotFoundException.class);
    }

    @Test
    @DisplayName("기업 조회 - null ID")
    void getCorporationById_NullId() {
        // when & then
        assertThatThrownBy(() -> corporationService.getCorporationById(null))
                .isInstanceOf(InvalidParameterException.class);
    }

    @Test
    @DisplayName("기업 조회 - 잘못된 ID (0)")
    void getCorporationById_InvalidId() {
        // when & then
        assertThatThrownBy(() -> corporationService.getCorporationById(0L))
                .isInstanceOf(InvalidParameterException.class);
    }

    @Test
    @DisplayName("기업 목록 조회 - 필터링 없음")
    void getCorporationsWithFilters_NoFilter() {
        // given
        Corporation corp1 = Corporation.builder()
                .name("기업1")
                .isDomestic(1)
                .blogLink("https://blog1.com")
                .build();
        Corporation corp2 = Corporation.builder()
                .name("기업2")
                .isDomestic(0)
                .youtubeUrl("https://youtube.com/@test")
                .build();
        corporationRepository.save(corp1);
        corporationRepository.save(corp2);

        // when
        Page<CorporationResponseDto> result = corporationService.getCorporationsWithFilters(
                null, null, null, PageRequest.of(0, 10)
        );

        // then
        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("기업 목록 조회 - 검색어로 필터링")
    void getCorporationsWithFilters_WithSearch() {
        // given
        Corporation corp1 = Corporation.builder()
                .name("네이버")
                .isDomestic(1)
                .build();
        Corporation corp2 = Corporation.builder()
                .name("카카오")
                .isDomestic(1)
                .build();
        corporationRepository.save(corp1);
        corporationRepository.save(corp2);

        // when
        Page<CorporationResponseDto> result = corporationService.getCorporationsWithFilters(
                "네이버", null, null, PageRequest.of(0, 10)
        );

        // then
        assertThat(result).isNotNull();
        // 검색 결과 확인 (Specification 동작)
    }

    @Test
    @DisplayName("기업 수정 - 성공")
    void updateCorporation_Success() {
        // given
        Corporation corporation = Corporation.builder()
                .name("수정전기업")
                .isDomestic(1)
                .build();
        corporation = corporationRepository.save(corporation);

        CorporationUpdateDto dto = new CorporationUpdateDto();
        dto.setName("수정후기업");
        dto.setAlternateName("Updated Corp");
        dto.setHomeLink("https://updated.com");

        // when
        CorporationResponseDto result = corporationService.updateCorporation(corporation.getId(), dto);

        // then
        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("수정후기업");
    }

    @Test
    @DisplayName("기업 수정 - 존재하지 않는 기업")
    void updateCorporation_NotFound() {
        // given
        CorporationUpdateDto dto = new CorporationUpdateDto();
        dto.setName("수정기업");

        // when & then
        assertThatThrownBy(() -> corporationService.updateCorporation(999999L, dto))
                .isInstanceOf(CorporationNotFoundException.class);
    }

    @Test
    @DisplayName("기업 수정 - 다른 기업과 중복된 이름")
    void updateCorporation_DuplicateName() {
        // given
        Corporation corp1 = Corporation.builder()
                .name("기업1")
                .isDomestic(1)
                .build();
        Corporation corp2 = Corporation.builder()
                .name("기업2")
                .isDomestic(1)
                .build();
        corp1 = corporationRepository.save(corp1);
        corp2 = corporationRepository.save(corp2);

        CorporationUpdateDto dto = new CorporationUpdateDto();
        dto.setName("기업2"); // corp1을 corp2와 같은 이름으로 변경 시도

        // when & then
        Long corp1Id = corp1.getId();
        assertThatThrownBy(() -> corporationService.updateCorporation(corp1Id, dto))
                .isInstanceOf(DuplicateCorporationNameException.class);
    }

    @Test
    @DisplayName("기업 삭제 - 성공 (Soft Delete)")
    void deleteCorporation_Success() {
        // given
        Corporation corporation = Corporation.builder()
                .name("삭제기업")
                .isDomestic(1)
                .build();
        corporation = corporationRepository.save(corporation);
        Long corpId = corporation.getId();

        // when
        corporationService.deleteCorporation(corpId);

        // then
        Corporation deletedCorp = corporationRepository.findById(corpId).orElse(null);
        assertThat(deletedCorp).isNotNull();
        assertThat(deletedCorp.getDeletedAt()).isNotNull();
    }

    @Test
    @DisplayName("기업 삭제 - 존재하지 않는 기업")
    void deleteCorporation_NotFound() {
        // when & then
        assertThatThrownBy(() -> corporationService.deleteCorporation(999999L))
                .isInstanceOf(CorporationNotFoundException.class);
    }
}
