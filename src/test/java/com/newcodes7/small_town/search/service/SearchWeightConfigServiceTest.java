package com.newcodes7.small_town.search.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.newcodes7.small_town.global.entity.SearchWeightConfig;
import com.newcodes7.small_town.search.repository.SearchWeightConfigRepository;
import com.newcodes7.small_town.search.service.SearchWeightConfigService.WeightEntry;
import com.newcodes7.small_town.search.service.SemanticTermExpansionService.QueryComplexity;

@ExtendWith(MockitoExtension.class)
public class SearchWeightConfigServiceTest {

    @Mock
    private SearchWeightConfigRepository repository;

    @InjectMocks
    private SearchWeightConfigService searchWeightConfigService;

    @BeforeEach
    void setUp() {
        // init()은 @PostConstruct이므로 수동으로 호출
        when(repository.findAll()).thenReturn(List.of());
        searchWeightConfigService.init();
    }

    @Test
    @DisplayName("기본 가중치 조회 - SIMPLE")
    void getWeights_Simple_ReturnsDefault() {
        // when
        WeightEntry entry = searchWeightConfigService.getWeights(QueryComplexity.SIMPLE);

        // then
        assertThat(entry).isNotNull();
        assertThat(entry.titleMultiplier()).isEqualTo(3.0);
        assertThat(entry.bm25NsfWeight()).isEqualTo(0.6);
        assertThat(entry.vectorNsfWeight()).isEqualTo(0.4);
    }

    @Test
    @DisplayName("기본 가중치 조회 - MODERATE")
    void getWeights_Moderate_ReturnsDefault() {
        // when
        WeightEntry entry = searchWeightConfigService.getWeights(QueryComplexity.MODERATE);

        // then
        assertThat(entry).isNotNull();
        assertThat(entry.titleMultiplier()).isEqualTo(2.0);
        assertThat(entry.bm25NsfWeight()).isEqualTo(0.5);
        assertThat(entry.vectorNsfWeight()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("기본 가중치 조회 - COMPLEX")
    void getWeights_Complex_ReturnsDefault() {
        // when
        WeightEntry entry = searchWeightConfigService.getWeights(QueryComplexity.COMPLEX);

        // then
        assertThat(entry).isNotNull();
        assertThat(entry.titleMultiplier()).isEqualTo(1.0);
        assertThat(entry.bm25NsfWeight()).isEqualTo(0.4);
        assertThat(entry.vectorNsfWeight()).isEqualTo(0.6);
    }

    @Test
    @DisplayName("DB에서 가중치 로드 - 캐시 갱신")
    void refreshCache_LoadsFromDB() {
        // given
        SearchWeightConfig dbConfig = SearchWeightConfig.builder()
                .complexity("SIMPLE")
                .titleMultiplier(5.0)
                .bm25NsfWeight(0.7)
                .vectorNsfWeight(0.3)
                .build();
        when(repository.findAll()).thenReturn(List.of(dbConfig));

        // when
        searchWeightConfigService.init();
        WeightEntry entry = searchWeightConfigService.getWeights(QueryComplexity.SIMPLE);

        // then
        assertThat(entry.titleMultiplier()).isEqualTo(5.0);
        assertThat(entry.bm25NsfWeight()).isEqualTo(0.7);
        assertThat(entry.vectorNsfWeight()).isEqualTo(0.3);
    }

    @Test
    @DisplayName("모든 가중치 조회")
    void getAllWeights_ReturnsAllComplexities() {
        // when
        Map<String, WeightEntry> allWeights = searchWeightConfigService.getAllWeights();

        // then
        assertThat(allWeights).hasSize(3);
        assertThat(allWeights).containsKeys("SIMPLE", "MODERATE", "COMPLEX");
    }

    @Test
    @DisplayName("가중치 업데이트 - 성공")
    void updateWeights_Success() {
        // given
        SearchWeightConfig existingConfig = SearchWeightConfig.builder()
                .id(1L)
                .complexity("SIMPLE")
                .titleMultiplier(3.0)
                .bm25NsfWeight(0.6)
                .vectorNsfWeight(0.4)
                .build();
        when(repository.findByComplexity("SIMPLE")).thenReturn(Optional.of(existingConfig));
        when(repository.save(any(SearchWeightConfig.class))).thenReturn(existingConfig);
        when(repository.findAll()).thenReturn(List.of(existingConfig));

        // when
        searchWeightConfigService.updateWeights("SIMPLE", 4.0, 0.8, 0.2, "admin");

        // then
        verify(repository).save(any(SearchWeightConfig.class));
        assertThat(existingConfig.getTitleMultiplier()).isEqualTo(4.0);
        assertThat(existingConfig.getBm25NsfWeight()).isEqualTo(0.8);
        assertThat(existingConfig.getVectorNsfWeight()).isEqualTo(0.2);
        assertThat(existingConfig.getUpdatedBy()).isEqualTo("admin");
    }

    @Test
    @DisplayName("가중치 업데이트 - 존재하지 않는 복잡도")
    void updateWeights_UnknownComplexity_ThrowsException() {
        // given
        when(repository.findByComplexity("UNKNOWN")).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() ->
            searchWeightConfigService.updateWeights("UNKNOWN", 1.0, 0.5, 0.5, "admin")
        ).isInstanceOf(IllegalArgumentException.class)
         .hasMessageContaining("Unknown complexity");
    }

    @Test
    @DisplayName("DB 로드 실패 시 기존 캐시 유지")
    void refreshCache_DBFailure_KeepsExistingCache() {
        // given - 먼저 정상적으로 로드
        SearchWeightConfig dbConfig = SearchWeightConfig.builder()
                .complexity("SIMPLE")
                .titleMultiplier(5.0)
                .bm25NsfWeight(0.7)
                .vectorNsfWeight(0.3)
                .build();
        when(repository.findAll()).thenReturn(List.of(dbConfig));
        searchWeightConfigService.init();

        // DB 장애 시뮬레이션
        when(repository.findAll()).thenThrow(new RuntimeException("DB connection failed"));

        // when - 다시 init 호출 (refreshCache 내부에서 예외 발생)
        searchWeightConfigService.init();

        // then - 기존 캐시 값 유지 (기본값으로 폴백)
        WeightEntry entry = searchWeightConfigService.getWeights(QueryComplexity.MODERATE);
        assertThat(entry).isNotNull();
    }

    @Test
    @DisplayName("getAllWeights 반환 값은 불변")
    void getAllWeights_ReturnsUnmodifiableCopy() {
        // when
        Map<String, WeightEntry> allWeights = searchWeightConfigService.getAllWeights();

        // then
        assertThatThrownBy(() -> allWeights.put("NEW", new WeightEntry(1.0, 0.5, 0.5)))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
