package com.newcodes7.small_town.corporation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import com.newcodes7.small_town.corporation.dto.CorporationCreateDto;
import com.newcodes7.small_town.corporation.exception.InvalidParameterException;
import com.newcodes7.small_town.corporation.repository.CorporationRepository;
import com.newcodes7.small_town.corporation.repository.IndustryRepository;
import com.newcodes7.small_town.crawler.entity.ParsingSelector;
import com.newcodes7.small_town.crawler.integration.youtube.YouTubeService;
import com.newcodes7.small_town.crawler.repository.ParsingSelectorRepository;
import com.newcodes7.small_town.global.cache.NginxCachePurgeService;
import com.newcodes7.small_town.global.entity.Corporation;

@ExtendWith(MockitoExtension.class)
class CorporationServiceTest {

    @Mock
    private CorporationRepository corporationRepository;
    @Mock
    private IndustryRepository industryRepository;
    @Mock
    private FileUploadService fileUploadService;
    @Mock
    private ParsingSelectorRepository parsingSelectorRepository;
    @Mock
    private NginxCachePurgeService nginxCachePurgeService;
    @Mock
    private YouTubeService youtubeService;
    @Mock
    private MultipartFile multipartFile;

    @InjectMocks
    private CorporationService corporationService;

    @Test
    void createCorporation_throwsWhenDtoIsNull() {
        assertThatThrownBy(() -> corporationService.createCorporation(null))
                .isInstanceOf(InvalidParameterException.class);

        verify(corporationRepository, never()).save(any());
    }

    @Test
    void createCorporation_appliesDecompositionAndParsingSelectorDefaults() {
        CorporationCreateDto dto = CorporationCreateDto.builder()
                .name("당근")
                .alternateName("Carrot")
                .youtubeUrl("https://youtube.com/@danggeun")
                .publishType(null)
                .build();

        when(corporationRepository.existsByNameAndDeletedAtIsNull("당근")).thenReturn(false);
        when(youtubeService.getChannelIdFromUrl(dto.getYoutubeUrl())).thenReturn("channel-1");
        when(corporationRepository.save(any(Corporation.class))).thenAnswer(invocation -> {
            Corporation corporation = invocation.getArgument(0);
            corporation.setId(11L);
            return corporation;
        });

        corporationService.createCorporation(dto);

        ArgumentCaptor<Corporation> corporationCaptor = ArgumentCaptor.forClass(Corporation.class);
        verify(corporationRepository).save(corporationCaptor.capture());
        Corporation savedCorporation = corporationCaptor.getValue();

        assertThat(savedCorporation.getYoutubeChannelId()).isEqualTo("channel-1");
        assertThat(savedCorporation.getDecomposedName()).isNotBlank();
        assertThat(savedCorporation.getChosungName()).isNotBlank();
        assertThat(savedCorporation.getDecomposedAlternateName()).isNotBlank();
        assertThat(savedCorporation.getChosungAlternateName()).isNotBlank();

        ArgumentCaptor<ParsingSelector> selectorCaptor = ArgumentCaptor.forClass(ParsingSelector.class);
        verify(parsingSelectorRepository).save(selectorCaptor.capture());

        ParsingSelector savedSelector = selectorCaptor.getValue();
        assertThat(savedSelector.getCorporationId()).isEqualTo(11L);
        assertThat(savedSelector.getPublishType()).isEqualTo("OUTER");
    }

    @Test
    void createCorporationWithLogo_keepsFlowWhenYoutubeLookupFails() throws IOException {
        CorporationCreateDto dto = CorporationCreateDto.builder()
                .name("토스")
                .alternateName(" ")
                .youtubeUrl("https://youtube.com/@toss")
                .build();

        when(corporationRepository.existsByNameAndDeletedAtIsNull("토스")).thenReturn(false);
        when(youtubeService.getChannelIdFromUrl(dto.getYoutubeUrl())).thenThrow(new RuntimeException("lookup failed"));
        when(corporationRepository.save(any(Corporation.class))).thenAnswer(invocation -> {
            Corporation corporation = invocation.getArgument(0);
            corporation.setId(22L);
            return corporation;
        });
        when(multipartFile.isEmpty()).thenReturn(true);

        corporationService.createCorporationWithLogo(dto, multipartFile);

        ArgumentCaptor<Corporation> corporationCaptor = ArgumentCaptor.forClass(Corporation.class);
        verify(corporationRepository).save(corporationCaptor.capture());
        Corporation savedCorporation = corporationCaptor.getValue();

        assertThat(savedCorporation.getYoutubeChannelId()).isNull();
        assertThat(savedCorporation.getDecomposedAlternateName()).isNull();
        assertThat(savedCorporation.getChosungAlternateName()).isNull();

        verify(fileUploadService, never()).saveLogoFileToS3(any(), any());
        verify(fileUploadService, never()).saveLogoFile(any(), anyLong());
    }
}
