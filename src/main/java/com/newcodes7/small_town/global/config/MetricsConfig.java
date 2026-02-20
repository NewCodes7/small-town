package com.newcodes7.small_town.global.config;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.http.server.observation.ServerRequestObservationConvention;

@Configuration
public class MetricsConfig {

    @Bean
    public ServerRequestObservationConvention serverRequestObservationConvention() {
        return new DefaultServerRequestObservationConvention() {
            @Override
            public KeyValues getLowCardinalityKeyValues(ServerRequestObservationContext context) {
                String keyword = context.getCarrier().getParameter("keyword");
                boolean hasKeyword = keyword != null && !keyword.trim().isEmpty();
                return super.getLowCardinalityKeyValues(context)
                        .and(KeyValue.of("has_keyword", String.valueOf(hasKeyword)));
            }
        };
    }
}
