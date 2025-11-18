package com.newcodes7.small_town.crawler.config;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.openqa.selenium.PageLoadStrategy;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.springframework.context.annotation.Configuration;

import io.github.bonigarcia.wdm.WebDriverManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class WebDriverConfig {
    
    private final WebDriverProperties webDriverProperties;
    
    public WebDriver createWebDriver() {
        WebDriverManager.chromedriver().setup(); // 크롬 드라이버 자동 다운로드 및 설정

        ChromeOptions options = new ChromeOptions();

        // GUI 없이 백그라운드에서 실행하도록 설정
        options.addArguments("--headless=new");

        // 해외 블로그의 경우 한국 시간으로 보기 위해 설정 
        options.addArguments("--timezone=Asia/Seoul");

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

        // Medium bot 감지 우회를 위한 추가 옵션들
        options.addArguments("--disable-blink-features=AutomationControlled");
        options.addArguments("--disable-extensions");
        options.addArguments("--disable-plugins");
        options.addArguments("--no-first-run");
        options.addArguments("--disable-default-apps");
        options.addArguments("--disable-popup-blocking");
        options.addArguments("--ignore-certificate-errors");
        options.addArguments("--ignore-ssl-errors");
        options.addArguments("--allow-running-insecure-content");

        options.setPageLoadStrategy(PageLoadStrategy.EAGER);
        
        // 자동화 감지 방지
        options.setExperimentalOption("useAutomationExtension", false);
        options.setExperimentalOption("excludeSwitches", new String[]{"enable-automation"});
        
        // 추가 preferences 설정
        Map<String, Object> prefs = new HashMap<>();
        prefs.put("profile.default_content_setting_values.notifications", 2); // 알림 차단
        prefs.put("profile.default_content_settings.popups", 0); // 팝업 차단
        prefs.put("profile.managed_default_content_settings.images", 2); // 이미지 차단
        options.setExperimentalOption("prefs", prefs);

        ChromeDriver driver = new ChromeDriver(options);
        // 웹 요소가 나타날 때까지 기다리는 최대 시간을 설정
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(webDriverProperties.getTimeout()));
        // 페이지가 완전히 로드될 때까지 기다리는 최대 시간을 설정
        driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(webDriverProperties.getTimeout()));

        log.info("WebDriver 초기화 완료 - Headless: {}", webDriverProperties.isHeadless());

        return driver;
    }

    public void forceCloseWebDriver(WebDriver driver) {
        if (driver == null) return;

        int chromeCountBefore = countChromeProcesses();
        log.debug("WebDriver 종료 시작 - 현재 Chrome 프로세스: {}개", chromeCountBefore);

        try {
            driver.quit();
            log.info("driver.quit() 호출 완료");
        } catch (Exception e) {
            log.error("driver.quit() 실패: {}", e.getMessage());
        }

        // quit() 후 실제로 프로세스가 종료되었는지 확인
        try {
            Thread.sleep(1000); // 프로세스 종료 대기
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        int chromeCountAfter = countChromeProcesses();
        log.info("WebDriver 종료 후 Chrome 프로세스: {}개 (변화: {} → {})",
                 chromeCountAfter, chromeCountBefore, chromeCountAfter);

        // 프로세스가 남아있으면 강제 종료
        if (chromeCountAfter > 0) {
            log.warn("Chrome 프로세스가 {}개 남아있음 - 강제 종료 시도", chromeCountAfter);
            killZombieChromeProcesses();

            // 강제 종료 후 재확인
            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }

            int finalCount = countChromeProcesses();
            if (finalCount > 0) {
                log.error("강제 종료 후에도 Chrome 프로세스 {}개 남음 - 메모리 누수 위험", finalCount);
            } else {
                log.info("좀비 Chrome 프로세스 강제 종료 완료");
            }
        }
    }

    /**
     * 현재 실행 중인 Chrome 관련 프로세스 개수 확인
     */
    private int countChromeProcesses() {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{
                "sh", "-c", "ps aux | grep -E 'chrome|chromedriver' | grep -v grep | wc -l"
            });

            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()));
            String count = reader.readLine();
            reader.close();
            process.waitFor();

            return Integer.parseInt(count.trim());
        } catch (Exception e) {
            log.debug("Chrome 프로세스 개수 확인 실패: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 좀비 Chrome 프로세스 강제 종료
     */
    private void killZombieChromeProcesses() {
        try {
            // Chrome 프로세스 종료
            Process killChrome = Runtime.getRuntime().exec(new String[]{
                "sh", "-c", "pkill -9 -f 'chrome'"
            });
            killChrome.waitFor();

            // ChromeDriver도 종료
            Process killDriver = Runtime.getRuntime().exec(new String[]{
                "pkill", "-9", "-f", "chromedriver"
            });
            killDriver.waitFor();

            log.info("좀비 Chrome/ChromeDriver 프로세스 강제 종료 명령 실행");
        } catch (Exception e) {
            log.error("좀비 프로세스 강제 종료 실패: {}", e.getMessage());
        }
    }
}