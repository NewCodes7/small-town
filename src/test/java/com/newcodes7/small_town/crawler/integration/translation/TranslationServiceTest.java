package com.newcodes7.small_town.crawler.integration.translation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpClientErrorException;

import com.newcodes7.small_town.crawler.integration.deepl.DeeplService;

@ExtendWith(MockitoExtension.class)
class TranslationServiceTest {

    @Mock
    private DeeplService deeplService;

    @Mock
    private GoogleTranslateService googleTranslateService;

    @InjectMocks
    private TranslationService translationService;

    @Test
    @DisplayName("translateTitle: DeepL quota exceeded 시 Google Translation API로 폴백")
    void translateTitle_deeplQuotaExceeded_googleFallback() {
        HttpClientErrorException quotaExceeded = HttpClientErrorException.create(
            HttpStatusCode.valueOf(456),
            "Quota Exceeded",
            HttpHeaders.EMPTY,
            "Quota exceeded".getBytes(StandardCharsets.UTF_8),
            StandardCharsets.UTF_8
        );

        when(deeplService.translate("Hello world", "KO")).thenThrow(quotaExceeded);
        when(googleTranslateService.translate("Hello world", "KO")).thenReturn("구글 번역 제목");

        String result = translationService.translateTitle("Hello world");

        assertThat(result).isEqualTo("구글 번역 제목");
        verify(googleTranslateService).translate("Hello world", "KO");
    }

    @Test
    @DisplayName("translateTitle: quota 초과가 아니면 원문 반환")
    void translateTitle_nonQuotaFailure_returnsOriginal() {
        HttpClientErrorException failure = HttpClientErrorException.create(
            HttpStatusCode.valueOf(500),
            "Server Error",
            HttpHeaders.EMPTY,
            "internal error".getBytes(StandardCharsets.UTF_8),
            StandardCharsets.UTF_8
        );

        when(deeplService.translate("Hello world", "KO")).thenThrow(failure);

        String result = translationService.translateTitle("Hello world");

        assertThat(result).isEqualTo("Hello world");
        verifyNoInteractions(googleTranslateService);
    }
}
