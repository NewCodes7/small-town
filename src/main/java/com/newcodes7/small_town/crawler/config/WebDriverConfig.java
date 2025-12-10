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
        if (webDriverProperties.isHeadless()) {
            options.addArguments("--headless=new");
        }

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

        // ===== Medium bot 감지 우회를 위한 강화된 옵션들 =====
        // Automation 플래그 제거 (가장 중요)
        options.addArguments("--disable-blink-features=AutomationControlled");

        // 확장 프로그램 비활성화
        options.addArguments("--disable-extensions");
        options.addArguments("--disable-plugins");

        // 초기 실행 관련
        options.addArguments("--no-first-run");
        options.addArguments("--disable-default-apps");

        // 팝업 및 인증서
        options.addArguments("--disable-popup-blocking");
        options.addArguments("--ignore-certificate-errors");
        options.addArguments("--ignore-ssl-errors");
        options.addArguments("--allow-running-insecure-content");

        // 추가 bot 감지 우회 옵션들
        options.addArguments("--disable-web-security");
        options.addArguments("--disable-features=IsolateOrigins,site-per-process");
        options.addArguments("--allow-insecure-localhost");
        options.addArguments("--disable-infobars");
        options.addArguments("--disable-logging");
        options.addArguments("--disable-login-animations");
        options.addArguments("--disable-notifications");
        options.addArguments("--disable-background-timer-throttling");
        options.addArguments("--disable-backgrounding-occluded-windows");
        options.addArguments("--disable-renderer-backgrounding");
        options.addArguments("--disable-features=TranslateUI");
        options.addArguments("--disable-ipc-flooding-protection");
        options.addArguments("--enable-features=NetworkService,NetworkServiceInProcess");

        // 언어 설정 (한국어 + 영어)
        options.addArguments("--lang=ko-KR");
        options.addArguments("--accept-lang=ko-KR,ko,en-US,en");

        // 메모리 효율성 개선 옵션들 (Chrome 안정성 유지하면서 최적화)
        options.addArguments("--disable-software-rasterizer");  // SW 렌더링 비활성화
        options.addArguments("--disable-webgl");                // WebGL 비활성화
        options.addArguments("--disable-web-security");         // 보안 기능 비활성화 (메모리 절약)
        options.addArguments("--disable-cache");                // 캐시 비활성화
        options.addArguments("--disable-application-cache");
        options.addArguments("--disk-cache-size=0");            // 디스크 캐시 크기 0

        // 주의: --single-process는 Chrome 크래시 유발 가능하므로 제거
        // 주의: --no-zygote는 headless 모드에서 문제 발생 가능하므로 제거

        options.setPageLoadStrategy(PageLoadStrategy.NORMAL);

        // 자동화 감지 방지 (가장 중요!)
        options.setExperimentalOption("useAutomationExtension", false);
        options.setExperimentalOption("excludeSwitches", new String[]{"enable-automation", "enable-logging"});

        // 추가 preferences 설정
        Map<String, Object> prefs = new HashMap<>();
        prefs.put("profile.default_content_setting_values.notifications", 2); // 알림 차단
        prefs.put("profile.default_content_settings.popups", 0); // 팝업 차단
        // 이미지 로딩 활성화 (bot 감지 우회를 위해)
        prefs.put("profile.managed_default_content_settings.images", 1); // 이미지 허용으로 변경
        prefs.put("credentials_enable_service", false);
        prefs.put("profile.password_manager_enabled", false);
        // DevTools 자동 열림 방지
        prefs.put("devtools.preferences.currentDockState", "\"undocked\"");
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

        log.debug("WebDriver 종료 시작");

        try {
            driver.quit();
            log.info("driver.quit() 호출 완료");
        } catch (Exception e) {
            log.warn("driver.quit() 실패 (무시하고 계속): {}", e.getMessage());
        }

        // quit() 후 프로세스 종료 대기
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        // Chrome 프로세스가 남아있는지 확인하고 강제 종료
        int remainingProcesses = countChromeProcesses();
        if (remainingProcesses > 0) {
            log.warn("Chrome 프로세스 {}개가 남아있습니다. 강제 종료를 시도합니다.", remainingProcesses);
            killZombieChromeProcesses();
        } else {
            log.debug("모든 Chrome 프로세스가 정상 종료되었습니다.");
        }
    }

    /**
     * 현재 실행 중인 Chrome 관련 프로세스 개수 확인
     * Zombie 프로세스는 제외하고 실제 실행 중인 프로세스만 카운트
     */
    private int countChromeProcesses() {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                "sh", "-c", "ps aux | grep -E 'chrome|chromedriver' | grep -v grep | grep -v '<defunct>' | wc -l"
            );
            Process process = processBuilder.start();

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
     * 좀비가 아닌 실제 Chrome 프로세스만 강제 종료
     * Zombie 프로세스는 이미 죽어있어서 kill 불가능하므로 제외
     */
    private void killZombieChromeProcesses() {
        try {
            // Zombie가 아닌 Chrome 프로세스만 종료 (State가 Z가 아닌 것만)
            // awk로 defunct가 아닌 프로세스의 PID만 추출하여 kill
            ProcessBuilder processBuilder = new ProcessBuilder(
                "sh", "-c",
                "ps aux | grep -E 'chrome|chromedriver' | grep -v grep | grep -v '<defunct>' | awk '{print $2}' | xargs -r kill -9"
            );
            Process killChrome = processBuilder.start();

            // Timeout 설정 (최대 3초 대기)
            boolean finished = killChrome.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);

            if (!finished) {
                log.warn("Chrome 프로세스 강제 종료 시간 초과 (3초) - 프로세스 강제 중단");
                killChrome.destroyForcibly();
            } else {
                log.info("실제 Chrome/ChromeDriver 프로세스 강제 종료 완료");
            }
        } catch (Exception e) {
            log.warn("Chrome 프로세스 강제 종료 중 오류 (무시하고 계속): {}", e.getMessage());
            // 오류가 나도 크롤링은 계속 진행
        }
    }
}