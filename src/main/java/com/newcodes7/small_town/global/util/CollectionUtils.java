package com.newcodes7.small_town.global.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Collection 관련 유틸리티 클래스
 */
public class CollectionUtils {

    private CollectionUtils() {
        throw new IllegalStateException("Utility class");
    }

    /**
     * null 또는 빈 리스트를 빈 리스트로 변환
     * Native Query에서 타입 추론 오류를 방지하기 위해 사용
     *
     * @param list 변환할 리스트
     * @param <T> 리스트 요소 타입
     * @return null이거나 비어있으면 새로운 빈 ArrayList, 그렇지 않으면 원본 리스트
     */
    public static <T> List<T> toEmptyIfNull(List<T> list) {
        return (list == null || list.isEmpty()) ? new ArrayList<>() : list;
    }
}
