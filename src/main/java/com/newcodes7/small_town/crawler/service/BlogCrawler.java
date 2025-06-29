package com.newcodes7.small_town.crawler.service;

import com.newcodes7.small_town.crawler.entity.Article;
import com.newcodes7.small_town.crawler.entity.Corporation;
import com.newcodes7.small_town.crawler.exception.CrawlerException;
import org.openqa.selenium.WebDriver;

import java.util.List;

public interface BlogCrawler {
    boolean canHandle(String blogUrl);
    List<Article> crawl(WebDriver driver, Corporation corporation) throws CrawlerException;
    String getProviderName();
}