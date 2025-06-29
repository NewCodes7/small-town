package com.newcodes7.small_town.crawler.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RobotsTxtRule {
    
    private String userAgent;
    @Builder.Default
    private List<String> allowedPaths = new ArrayList<>();
    @Builder.Default
    private List<String> disallowedPaths = new ArrayList<>();
    private Long crawlDelay; // seconds
    private String sitemapUrl;
    
    /**
     * 주어진 경로가 크롤링 허용되는지 확인
     * @param path 확인할 경로
     * @return true if allowed, false if disallowed
     */
    public boolean isPathAllowed(String path) {
        if (path == null) {
            return false;
        }
        
        // 더 구체적인 규칙을 찾기 위해 매칭되는 패턴들을 수집
        String bestAllowPattern = null;
        String bestDisallowPattern = null;
        int allowPatternLength = -1;
        int disallowPatternLength = -1;
        
        // 명시적으로 허용된 경로 중 가장 구체적인 것 찾기
        for (String allowedPath : allowedPaths) {
            if (pathMatches(path, allowedPath)) {
                if (allowedPath.length() > allowPatternLength) {
                    bestAllowPattern = allowedPath;
                    allowPatternLength = allowedPath.length();
                }
            }
        }
        
        // 명시적으로 금지된 경로 중 가장 구체적인 것 찾기
        for (String disallowedPath : disallowedPaths) {
            if (pathMatches(path, disallowedPath)) {
                if (disallowedPath.length() > disallowPatternLength) {
                    bestDisallowPattern = disallowedPath;
                    disallowPatternLength = disallowedPath.length();
                }
            }
        }
        
        // 가장 구체적인 규칙이 우선 (더 긴 패턴이 더 구체적)
        if (bestAllowPattern != null && bestDisallowPattern != null) {
            return allowPatternLength >= disallowPatternLength;
        }
        
        // Allow 규칙만 매치되면 허용
        if (bestAllowPattern != null) {
            return true;
        }
        
        // Disallow 규칙만 매치되면 금지
        if (bestDisallowPattern != null) {
            return false;
        }
        
        // 어떤 규칙도 매치되지 않으면 기본적으로 허용
        return true;
    }
    
    /**
     * 경로 패턴 매칭 (와일드카드 지원)
     * @param path 실제 경로
     * @param pattern robots.txt의 패턴
     * @return true if matches
     */
    private boolean pathMatches(String path, String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return false;
        }
        
        // 정확한 매치
        if (path.equals(pattern)) {
            return true;
        }
        
        // 와일드카드 처리
        if (pattern.endsWith("*")) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            return path.startsWith(prefix);
        }
        
        // 디렉토리 패턴 처리
        if (pattern.endsWith("/")) {
            return path.startsWith(pattern) || path.equals(pattern.substring(0, pattern.length() - 1));
        }
        
        // 접두사 매칭
        return path.startsWith(pattern);
    }
}