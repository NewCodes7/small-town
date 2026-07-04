package com.newcodes7.small_town.global.config;

import org.apache.catalina.connector.Connector;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Tomcat 서버 설정
 *
 * OOM 등으로 서버가 비정상 종료 후 재시작될 때 포트 충돌을 방지하기 위한 설정
 */
@Configuration
public class TomcatConfig {

    /**
     * Tomcat 커넥터에 SO_REUSEADDR 옵션을 활성화
     *
     * 이 옵션은 TIME_WAIT 상태의 포트를 즉시 재사용할 수 있게 합니다.
     * OOM으로 서버가 비정상 종료되었을 때, 포트가 TIME_WAIT 상태로 남아있어도
     * 재시작 시 같은 포트를 사용할 수 있습니다.
     */
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> tomcatCustomizer() {
        return factory -> {
            factory.addConnectorCustomizers((Connector connector) -> {
                // SO_REUSEADDR 활성화 (포트 재사용 허용)
                connector.setProperty("socket.soReuseAddress", "true");

                // TCP TIME_WAIT 상태에서도 포트 바인딩 허용
                connector.setProperty("socket.soTimeout", "60000");

                // 백로그 큐 크기 설정 (동시 연결 대기 큐)
                connector.setProperty("socket.appReadBufSize", "8192");
                connector.setProperty("socket.appWriteBufSize", "8192");
            });
        };
    }
}
