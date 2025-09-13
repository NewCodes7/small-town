package com.newcodes7.small_town.config;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.mockito.Mockito;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;

import com.newcodes7.small_town.crawler.config.WebDriverConfig;

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
            
            ClassPathResource htmlResource = new ClassPathResource("toss_blog.html");
            String htmlContent;
            try (InputStream inputStream = htmlResource.getInputStream()) {
                htmlContent = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }
            
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