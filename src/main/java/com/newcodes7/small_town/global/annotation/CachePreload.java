package com.newcodes7.small_town.global.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 캐시 삭제 후 자동으로 캐시를 프리로드하는 어노테이션
 * @CacheEvict와 함께 사용하여 캐시 삭제 직후 주요 데이터를 미리 로드
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CachePreload {

    /**
     * 프리로드할 캐시 이름
     */
    String value() default "corporationArticles";

    /**
     * 프리로드 활성화 여부
     */
    boolean enabled() default true;
}
