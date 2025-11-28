package com.newcodes7.small_town.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import kr.co.shineware.nlp.komoran.constant.DEFAULT_MODEL;
import kr.co.shineware.nlp.komoran.core.Komoran;
import lombok.extern.slf4j.Slf4j;

@Configuration
@Slf4j
public class KomoranConfig {

    /**
     * Komoran FULL 모델을 Bean으로 등록
     * FULL 모델은 더 많은 어휘와 높은 정확도를 제공
     *
     * Singleton으로 관리되어 메모리 효율적
     */
    @Bean
    public Komoran komoran() {
        log.info("Komoran FULL 모델 초기화 시작");
        long startTime = System.currentTimeMillis();

        Komoran komoran = new Komoran(DEFAULT_MODEL.FULL);

        long endTime = System.currentTimeMillis();
        log.info("Komoran FULL 모델 초기화 완료 (소요 시간: {}ms)", endTime - startTime);

        return komoran;
    }
}
