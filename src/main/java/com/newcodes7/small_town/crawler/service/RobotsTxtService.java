package com.newcodes7.small_town.crawler.service;

import com.newcodes7.small_town.crawler.dto.RobotsTxtRule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class RobotsTxtService {
    
    // 캐시: URL -> RobotsTxtRule (메모리 기반, 실제 운영에서는 Redis 등 사용 고려)
    private final Map<String, RobotsTxtRule> robotsCache = new ConcurrentHashMap<>();
    private final Map<String, Long> cacheTimestamps = new ConcurrentHashMap<>();
    
    // 캐시 유효 시간 (24시간)
    private static final long CACHE_DURATION = 24 * 60 * 60 * 1000L;
    
    // User-Agent 이름
    private static final String USER_AGENT = "SmallTownBot";
    
    /**
     * 주어진 URL에 대한 robots.txt 규칙을 가져옴
     * @param baseUrl 기본 URL (예: https://example.com)
     * @return RobotsTxtRule 객체
     */
    public RobotsTxtRule getRobotsTxtRules(String baseUrl) {
        if (baseUrl == null || baseUrl.isEmpty()) {
            return createDefaultAllowRule();
        }
        
        try {
            String normalizedUrl = normalizeBaseUrl(baseUrl);
            
            // 캐시 확인
            RobotsTxtRule cachedRule = getCachedRule(normalizedUrl);
            if (cachedRule != null) {
                return cachedRule;
            }
            
            // robots.txt 다운로드 및 파싱
            String robotsTxtUrl = normalizedUrl + "/robots.txt";
            String robotsTxtContent = downloadRobotsTxt(robotsTxtUrl);
            
            RobotsTxtRule rule;
            if (robotsTxtContent != null) {
                rule = parseRobotsTxt(robotsTxtContent);
                log.info("robots.txt를 성공적으로 파싱했습니다. URL: {}", robotsTxtUrl);
            } else {
                rule = createDefaultAllowRule();
                log.info("robots.txt가 없어서 기본 허용 규칙을 적용합니다. URL: {}", robotsTxtUrl);
            }
            
            // 캐시에 저장
            cacheRule(normalizedUrl, rule);
            return rule;
            
        } catch (Exception e) {
            log.error("robots.txt 처리 중 오류 발생: {}", baseUrl, e);
            return createDefaultAllowRule();
        }
    }
    
    /**
     * 특정 경로가 크롤링 가능한지 확인
     * @param baseUrl 기본 URL
     * @param path 확인할 경로
     * @return true if allowed, false if disallowed
     */
    public boolean isPathAllowed(String baseUrl, String path) {
        RobotsTxtRule rule = getRobotsTxtRules(baseUrl);
        boolean isAllowed = rule.isPathAllowed(path);
        log.debug("경로 허용 확인 - 베이스URL: {}, 경로: {}, User-Agent: {}, 허용: {}", baseUrl, path, rule.getUserAgent(), isAllowed);
        log.debug("적용된 규칙 - Disallow: {}, Allow: {}", rule.getDisallowedPaths(), rule.getAllowedPaths());
        return isAllowed;
    }
    
    /**
     * 크롤링 지연 시간 가져오기
     * @param baseUrl 기본 URL
     * @return 지연 시간 (초), null이면 지연 없음
     */
    public Long getCrawlDelay(String baseUrl) {
        RobotsTxtRule rule = getRobotsTxtRules(baseUrl);
        return rule.getCrawlDelay();
    }
    
    /**
     * robots.txt 내용을 다운로드
     */
    private String downloadRobotsTxt(String robotsTxtUrl) {
        try {
            URL url = new URL(robotsTxtUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            
            // User-Agent 설정
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setConnectTimeout(10000); // 10초
            connection.setReadTimeout(10000);    // 10초
            connection.setRequestMethod("GET");
            
            int responseCode = connection.getResponseCode();
            
            // 200 OK인 경우에만 내용 읽기
            if (responseCode == HttpURLConnection.HTTP_OK) {
                Scanner scanner = new Scanner(connection.getInputStream(), "UTF-8");
                StringBuilder content = new StringBuilder();
                
                while (scanner.hasNextLine()) {
                    content.append(scanner.nextLine()).append("\n");
                }
                scanner.close();
                
                return content.toString();
            } else {
                log.debug("robots.txt 응답 코드: {} for URL: {}", responseCode, robotsTxtUrl);
                return null;
            }
            
        } catch (MalformedURLException e) {
            log.debug("잘못된 robots.txt URL: {}", robotsTxtUrl);
            return null;
        } catch (IOException e) {
            log.debug("robots.txt 다운로드 실패: {}", robotsTxtUrl);
            return null;
        }
    }
    
    /**
     * robots.txt 내용을 파싱
     */
    private RobotsTxtRule parseRobotsTxt(String content) {
        String[] lines = content.split("\n");
        
        // 우리 봇에 대한 직접적인 규칙 찾기
        RobotsTxtRule specificRule = findRulesForUserAgent(lines, USER_AGENT);
        if (specificRule != null) {
            log.debug("SmallTownBot에 대한 특정 규칙 발견");
            return specificRule;
        }
        
        // SmallTownBot으로도 찾기
        specificRule = findRulesForUserAgent(lines, "SmallTownBot");
        if (specificRule != null) {
            log.debug("SmallTownBot에 대한 특정 규칙 발견");
            return specificRule;
        }
        
        // * (모든 봇) 규칙 찾기
        RobotsTxtRule generalRule = findRulesForUserAgent(lines, "*");
        if (generalRule != null) {
            log.debug("User-Agent: * 규칙 적용");
            return generalRule;
        }
        
        // 기본 허용 규칙
        log.debug("적용할 robots.txt 규칙 없음 - 기본 허용");
        return RobotsTxtRule.builder()
            .userAgent("*")
            .allowedPaths(new ArrayList<>())
            .disallowedPaths(new ArrayList<>())
            .crawlDelay(null)
            .sitemapUrl(null)
            .build();
    }
    
    /**
     * 특정 User-Agent에 대한 규칙 찾기
     */
    private RobotsTxtRule findRulesForUserAgent(String[] lines, String targetUserAgent) {
        List<String> allowedPaths = new ArrayList<>();
        List<String> disallowedPaths = new ArrayList<>();
        Long crawlDelay = null;
        String sitemapUrl = null;
        boolean inTargetUserAgentBlock = false;
        boolean foundTargetBlock = false;
        
        for (String line : lines) {
            line = line.trim();
            
            // 주석이나 빈 줄 무시
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            
            String[] parts = line.split(":", 2);
            if (parts.length != 2) {
                continue;
            }
            
            String directive = parts[0].trim().toLowerCase();
            String value = parts[1].trim();
            
            switch (directive) {
                case "user-agent":
                    // 타겟 User-Agent인지 확인
                    if (value.equals(targetUserAgent) || 
                        (targetUserAgent.equals(USER_AGENT) && value.equalsIgnoreCase(USER_AGENT))) {
                        inTargetUserAgentBlock = true;
                        foundTargetBlock = true;
                        log.debug("타겟 User-Agent 발견: {}", targetUserAgent);
                    } else {
                        // 다른 User-Agent이지만 이미 타겟 블록에 있으면 블록 종료하지 않음
                        // (여러 User-Agent가 같은 규칙을 공유할 수 있음)
                        if (foundTargetBlock && !hasAnyRules(allowedPaths, disallowedPaths, crawlDelay)) {
                            // 아직 규칙이 없으면 계속 현재 블록에 머무름
                            log.debug("타겟 User-Agent 후 다른 User-Agent 발견하지만 규칙 대기 중: {}", value);
                        } else if (foundTargetBlock && hasAnyRules(allowedPaths, disallowedPaths, crawlDelay)) {
                            // 이미 규칙이 있으면 새로운 블록 시작으로 간주하고 종료
                            log.debug("타겟 User-Agent 블록 종료 (새 블록 시작): {}", value);
                            return buildRule(targetUserAgent, allowedPaths, disallowedPaths, crawlDelay, sitemapUrl);
                        }
                    }
                    break;
                    
                case "disallow":
                    if (inTargetUserAgentBlock) {
                        if (!value.isEmpty()) {
                            disallowedPaths.add(value);
                            log.debug("Disallow 규칙 추가: {}", value);
                        }
                    }
                    break;
                    
                case "allow":
                    if (inTargetUserAgentBlock) {
                        if (!value.isEmpty()) {
                            allowedPaths.add(value);
                            log.debug("Allow 규칙 추가: {}", value);
                        }
                    }
                    break;
                    
                case "crawl-delay":
                    if (inTargetUserAgentBlock) {
                        try {
                            crawlDelay = Long.parseLong(value);
                            log.debug("Crawl-delay 설정: {}", crawlDelay);
                        } catch (NumberFormatException e) {
                            log.debug("잘못된 crawl-delay 값: {}", value);
                        }
                    }
                    break;
                    
                case "sitemap":
                    // Sitemap는 전역 설정
                    if (sitemapUrl == null) {
                        sitemapUrl = value;
                    }
                    break;
            }
        }
        
        // 타겟 블록을 찾았으면 규칙 반환
        if (foundTargetBlock) {
            return buildRule(targetUserAgent, allowedPaths, disallowedPaths, crawlDelay, sitemapUrl);
        }
        
        return null;
    }
    
    private RobotsTxtRule buildRule(String userAgent, List<String> allowedPaths, List<String> disallowedPaths, Long crawlDelay, String sitemapUrl) {
        return RobotsTxtRule.builder()
            .userAgent(userAgent)
            .allowedPaths(allowedPaths)
            .disallowedPaths(disallowedPaths)
            .crawlDelay(crawlDelay)
            .sitemapUrl(sitemapUrl)
            .build();
    }
    
    private boolean hasAnyRules(List<String> allowedPaths, List<String> disallowedPaths, Long crawlDelay) {
        return !allowedPaths.isEmpty() || !disallowedPaths.isEmpty() || crawlDelay != null;
    }
    
    /**
     * 기본 허용 규칙 생성
     */
    private RobotsTxtRule createDefaultAllowRule() {
        return RobotsTxtRule.builder()
            .userAgent("*")
            .allowedPaths(new ArrayList<>())
            .disallowedPaths(new ArrayList<>())
            .build();
    }
    
    /**
     * URL 정규화
     */
    private String normalizeBaseUrl(String url) {
        try {
            URL parsedUrl = new URL(url);
            return parsedUrl.getProtocol() + "://" + parsedUrl.getHost() + 
                   (parsedUrl.getPort() != -1 && parsedUrl.getPort() != 80 && parsedUrl.getPort() != 443 
                    ? ":" + parsedUrl.getPort() : "");
        } catch (MalformedURLException e) {
            return url;
        }
    }
    
    /**
     * 캐시에서 규칙 가져오기
     */
    private RobotsTxtRule getCachedRule(String baseUrl) {
        Long timestamp = cacheTimestamps.get(baseUrl);
        if (timestamp != null && (System.currentTimeMillis() - timestamp) < CACHE_DURATION) {
            return robotsCache.get(baseUrl);
        }
        return null;
    }
    
    /**
     * 캐시에 규칙 저장
     */
    private void cacheRule(String baseUrl, RobotsTxtRule rule) {
        robotsCache.put(baseUrl, rule);
        cacheTimestamps.put(baseUrl, System.currentTimeMillis());
    }
}