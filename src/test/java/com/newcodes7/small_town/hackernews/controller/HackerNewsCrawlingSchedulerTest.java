package com.newcodes7.small_town.hackernews.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.newcodes7.small_town.hackernews.service.HackerNewsService;

@ExtendWith(MockitoExtension.class)
class HackerNewsCrawlingSchedulerTest {

    @Mock
    private HackerNewsService hackerNewsService;

    @InjectMocks
    private HackerNewsCrawlingScheduler scheduler;

    @Test
    @DisplayName("scheduledHackerNewsCrawling: 정상 실행 시 crawlTopStoriesWithComments 호출")
    void scheduledHackerNewsCrawling_정상_실행() {
        // given
        when(hackerNewsService.crawlTopStoriesWithComments()).thenReturn(5);

        // when
        scheduler.scheduledHackerNewsCrawling();

        // then
        verify(hackerNewsService, times(1)).crawlTopStoriesWithComments();
    }

    @Test
    @DisplayName("scheduledHackerNewsCrawling: 예외 발생 시에도 정상 종료")
    void scheduledHackerNewsCrawling_예외_처리() {
        // given
        when(hackerNewsService.crawlTopStoriesWithComments()).thenThrow(new RuntimeException("API 오류"));

        // when & then - 예외가 전파되지 않아야 함
        assertThatCode(() -> scheduler.scheduledHackerNewsCrawling())
            .doesNotThrowAnyException();

        verify(hackerNewsService, times(1)).crawlTopStoriesWithComments();
    }
}
