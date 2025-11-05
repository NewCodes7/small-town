package com.newcodes7.small_town.crawler.service;

import java.util.List;

import org.openqa.selenium.WebDriver;

import com.newcodes7.small_town.crawler.exception.CrawlerException;
import com.newcodes7.small_town.global.entity.Corporation;
import com.newcodes7.small_town.global.entity.Video;

public interface VideoCrawler {
    boolean canHandle(String url);
    List<Video> crawl(WebDriver driver, Corporation corporation) throws CrawlerException;
    String getProviderName();

    /**
     * 이미지 업로드 처리 (중복이 아닌 경우에만 호출)
     * @param video 이미지 업로드할 비디오
     * @param corporation 기업 정보
     */
    default void processImageUpload(Video video, Corporation corporation) {
        // 기본 구현은 아무것도 하지 않음 (이미지 업로드 없음)
    }
}
