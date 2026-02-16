package com.newcodes7.small_town.crawler.util;

import com.newcodes7.small_town.crawler.config.WebDriverConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.WebDriver;
import org.springframework.stereotype.Component;

import java.util.function.Function;

/**
 * WebDriver 생명주기 관리를 위한 유틸리티 클래스
 * Try-catch-finally 패턴의 중복을 제거하고 안전한 WebDriver 사용을 보장
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebDriverExecutor {

    private final WebDriverConfig webDriverConfig;

    /**
     * WebDriver를 생성하고 작업을 실행한 후 자동으로 종료
     * 
     * @param operation WebDriver를 사용하는 작업
     * @param <T> 반환 타입
     * @return 작업 결과
     * @throws Exception 작업 중 발생한 예외
     */
    public <T> T execute(Function<WebDriver, T> operation) throws Exception {
        WebDriver driver = null;
        try {
            driver = webDriverConfig.createWebDriver();
            return operation.apply(driver);
        } finally {
            if (driver != null) {
                try {
                    webDriverConfig.forceCloseWebDriver(driver);
                } catch (Exception e) {
                    log.warn("WebDriver 종료 중 오류 발생: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * WebDriver를 생성하고 작업을 실행한 후 자동으로 종료 (예외 처리 포함)
     * 
     * @param operation WebDriver를 사용하는 작업
     * @param onError 예외 발생 시 실행할 콜백
     * @param <T> 반환 타입
     * @return 작업 결과 (실패 시 onError 반환값)
     */
    public <T> T executeWithErrorHandling(
            Function<WebDriver, T> operation,
            Function<Exception, T> onError) {
        WebDriver driver = null;
        try {
            driver = webDriverConfig.createWebDriver();
            return operation.apply(driver);
        } catch (Exception e) {
            log.error("WebDriver 작업 중 오류 발생: {}", e.getMessage(), e);
            return onError.apply(e);
        } finally {
            if (driver != null) {
                try {
                    webDriverConfig.forceCloseWebDriver(driver);
                } catch (Exception e) {
                    log.warn("WebDriver 종료 중 오류 발생: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * WebDriver를 생성하고 void 작업을 실행한 후 자동으로 종료
     * 
     * @param operation WebDriver를 사용하는 void 작업
     * @throws Exception 작업 중 발생한 예외
     */
    public void executeVoid(WebDriverConsumer operation) throws Exception {
        WebDriver driver = null;
        try {
            driver = webDriverConfig.createWebDriver();
            operation.accept(driver);
        } finally {
            if (driver != null) {
                try {
                    webDriverConfig.forceCloseWebDriver(driver);
                } catch (Exception e) {
                    log.warn("WebDriver 종료 중 오류 발생: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * WebDriver를 받아서 void 작업을 수행하는 함수형 인터페이스
     */
    @FunctionalInterface
    public interface WebDriverConsumer {
        void accept(WebDriver driver) throws Exception;
    }
}
