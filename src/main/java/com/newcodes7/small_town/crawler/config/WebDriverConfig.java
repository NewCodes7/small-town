package com.newcodes7.small_town.crawler.config;

import io.github.bonigarcia.wdm.WebDriverManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

import java.time.Duration;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class WebDriverConfig {
    
    private final WebDriverProperties webDriverProperties;
    
    @Bean
    @Scope("prototype")
    public WebDriver webDriver() {
        WebDriverManager.chromedriver().setup();
        
        ChromeOptions options = new ChromeOptions();
        
        if (webDriverProperties.isHeadless()) {
            options.addArguments("--headless");
        }
        
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--disable-gpu");
        options.addArguments("--window-size=" + webDriverProperties.getWindowSize());
        options.addArguments("--user-agent=" + webDriverProperties.getUserAgent());
        options.addArguments("--disable-blink-features=AutomationControlled");
        options.setExperimentalOption("useAutomationExtension", false);
        options.setExperimentalOption("excludeSwitches", new String[]{"enable-automation"});
        
        ChromeDriver driver = new ChromeDriver(options);
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(webDriverProperties.getTimeout()));
        driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(webDriverProperties.getTimeout()));
        
        log.info("WebDriver 초기화 완료 - Headless: {}", webDriverProperties.isHeadless());
        
        return driver;
    }
}