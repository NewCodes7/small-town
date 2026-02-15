package com.newcodes7.small_town.global.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.newcodes7.small_town.auth.repository.UserRepository;
import com.newcodes7.small_town.global.service.SearchLogService;

import java.util.Optional;

@WebMvcTest(SearchTrackingController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource("classpath:application-test.properties")
class SearchTrackingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private SearchLogService searchLogService;

    @MockBean
    private UserRepository userRepository;

    @Test
    void 검색_클릭_추적_성공() throws Exception {
        // given
        SearchTrackingController.SearchClickRequest request = new SearchTrackingController.SearchClickRequest();
        request.setKeyword("test keyword");
        request.setArticleId(10L);
        request.setPosition(1);

        doNothing().when(searchLogService).logSearchClick(
            any(), any(), any(), any(), any(), any(), any()
        );

        // when & then
        mockMvc.perform(post("/api/search/click")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));

        verify(searchLogService, times(1)).logSearchClick(
            any(), any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    void 검색_클릭_추적_Video() throws Exception {
        // given
        SearchTrackingController.SearchClickRequest request = new SearchTrackingController.SearchClickRequest();
        request.setKeyword("test video");
        request.setVideoId(20L);
        request.setPosition(2);
        request.setSearchLogId(5L);

        doNothing().when(searchLogService).logSearchClick(
            any(), any(), any(), any(), any(), any(), any()
        );

        // when & then
        mockMvc.perform(post("/api/search/click")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));
    }

    @Test
    void 인증_없이_클릭_추적() throws Exception {
        // given
        SearchTrackingController.SearchClickRequest request = new SearchTrackingController.SearchClickRequest();
        request.setKeyword("test");
        request.setArticleId(1L);
        request.setPosition(1);

        doNothing().when(searchLogService).logSearchClick(
            any(), any(), any(), any(), any(), any(), any()
        );

        // when & then - 인증 없이도 클릭 추적은 가능해야 함
        mockMvc.perform(post("/api/search/click")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }
}
