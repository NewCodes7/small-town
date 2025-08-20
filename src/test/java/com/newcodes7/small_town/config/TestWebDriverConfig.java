package com.newcodes7.small_town.config;

import org.mockito.Mockito;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.newcodes7.small_town.crawler.config.WebDriverConfig;

import java.io.File;
import java.nio.file.Files;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

@TestConfiguration
public class TestWebDriverConfig {
    
    @Bean
    @Primary
    public WebDriverConfig mockWebDriverConfig() {
        WebDriverConfig mockConfig = Mockito.mock(WebDriverConfig.class);
        
        try {
            WebDriver mockWebDriver = Mockito.mock(WebDriver.class, withSettings()
                    .extraInterfaces(JavascriptExecutor.class));

            JavascriptExecutor jsExecutor = (JavascriptExecutor) mockWebDriver;
            when(jsExecutor.executeScript(anyString())).thenReturn(100L);
            
            String htmlContent = Files.readString(
                new File("src/test/resources/toss_blog.html").toPath(),
                java.nio.charset.StandardCharsets.UTF_8
            );
            
            when(mockWebDriver.getPageSource()).thenReturn(htmlContent);
            when(mockConfig.createWebDriver()).thenReturn(mockWebDriver);
            
        } catch (Exception e) {
            // 기본값 설정
            WebDriver mockWebDriver = Mockito.mock(WebDriver.class);
            when(mockWebDriver.getPageSource()).thenReturn("<html><body>Test Content</body></html>");
            when(mockConfig.createWebDriver()).thenReturn(mockWebDriver);
        }
        
        return mockConfig;
    }
}