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
    @Scope("prototype") // 각 요청마다 새로운 WebDriver 인스턴스를 생성(크롤링은 독립적인 브라우저 인스턴스를 사용해야 하기에)
    public WebDriver webDriver() {
        WebDriverManager.chromedriver().setup(); // 크롬 드라이버 자동 다운로드 및 설정

        ChromeOptions options = new ChromeOptions();

        // GUI 없이 백그라운드에서 실행하도록 설정
        if (webDriverProperties.isHeadless()) {
            options.addArguments("--headless");
        }

        // TODO: 권한 문제로 인해 필요하지만, 보안 문제로 개선 필요 
        options.addArguments("--no-sandbox");
        // 도커(Docker)와 같은 컨테이너 환경에서 메모리 부족 문제를 방지하기 위해 사용
        options.addArguments("--disable-dev-shm-usage");
        // GPU 가속을 비활성화 (헤드리스 모드나 일부 환경에서 GPU 관련 문제를 방지하기 위해 사용)
        options.addArguments("--disable-gpu");
        // 브라우저 창의 크기 설정
        options.addArguments("--window-size=" + webDriverProperties.getWindowSize());
        // 특정 User-Agent를 필요로 하는 웹사이트에 접속할 때 사용
        options.addArguments("--user-agent=" + webDriverProperties.getUserAgent());

        // TODO: 더 자세한 원리 알아보기 
        // 웹사이트가 자동화된 브라우저(예: 셀레늄)임을 감지하는 것을 방지
        options.addArguments("--disable-blink-features=AutomationControlled");
        // 자동화 확장 프로그램 사용을 비활성화 (자동화 감지를 피하는 데 도움)
        options.setExperimentalOption("useAutomationExtension", false);
        // 특정 커맨드 라인 스위치를 제외 (여기서는 `enable-automation` 스위치를 제외하여 자동화 감지를 더욱 어렵게 만들기)
        options.setExperimentalOption("excludeSwitches", new String[]{"enable-automation"});

        ChromeDriver driver = new ChromeDriver(options);
        // 웹 요소가 나타날 때까지 기다리는 최대 시간을 설정
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(webDriverProperties.getTimeout()));
        // 페이지가 완전히 로드될 때까지 기다리는 최대 시간을 설정
        driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(webDriverProperties.getTimeout()));

        log.info("WebDriver 초기화 완료 - Headless: {}", webDriverProperties.isHeadless());

        return driver;
    }
}