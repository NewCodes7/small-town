package com.newcodes7.small_town.crawler.integration.deepl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class DeeplServiceTest {

    @Mock
    private RestTemplate restTemplate;

    private DeeplService deeplService;

    @BeforeEach
    void setUp() {
        deeplService = new DeeplService(restTemplate, new ObjectMapper());
        ReflectionTestUtils.setField(deeplService, "apiKey", "deepl-key");
    }

    @Test
    @DisplayName("translate: DeepL 응답의 첫 번역문을 반환")
    void translate_success() {
        when(restTemplate.exchange(
            eq("https://api-free.deepl.com/v2/translate"),
            eq(HttpMethod.POST),
            any(HttpEntity.class),
            eq(String.class)))
            .thenReturn(ResponseEntity.ok("{\"translations\":[{\"text\":\"번역된 제목\"}]}"));

        String result = deeplService.translate("Hello world", "KO");

        assertThat(result).isEqualTo("번역된 제목");
    }
}
