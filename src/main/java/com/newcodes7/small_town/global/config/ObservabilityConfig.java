package com.newcodes7.small_town.global.config;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.jdbc.datasource.JdbcTelemetry;

/**
 * 구간별 응답시간 추적(트레이싱) 설정.
 *
 * - ObservedAspect: {@code @Observed} 붙은 서비스 메서드를 trace span으로 기록
 * - DataSource 래핑: JDBC 쿼리 단위 span 기록 (Tempo 워터폴에서 DB 구간 확인)
 *   datasource-micrometer가 Boot 4 미지원이라 OTel JDBC 계측 라이브러리 사용
 */
@Configuration
public class ObservabilityConfig {

    @Bean
    public ObservedAspect observedAspect(ObservationRegistry observationRegistry) {
        return new ObservedAspect(observationRegistry);
    }

    // static: 다른 빈 정의보다 먼저 등록되어야 하는 BeanPostProcessor 규칙
    @Bean
    public static BeanPostProcessor jdbcTracingBeanPostProcessor(
            ObjectProvider<OpenTelemetry> openTelemetryProvider) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource dataSource) {
                    OpenTelemetry openTelemetry =
                            openTelemetryProvider.getIfAvailable(OpenTelemetry::noop);
                    return JdbcTelemetry.create(openTelemetry).wrap(dataSource);
                }
                return bean;
            }
        };
    }
}
