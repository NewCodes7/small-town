package com.newcodes7.small_town.crawler.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

@DisplayName("RSS 연결성 테스트")
class RssConnectivityTest {

    @Test
    @DisplayName("Netflix RSS 피드 URL 연결 테스트")
    void netflix_rss_연결_테스트() {
        String[] rssUrls = {
            "https://netflixtechblog.medium.com/feed",
            "https://medium.com/netflix-techblog/feed",
            "https://netflixtechblog.com/feed",
            "https://netflixtechblog.com/rss"
        };
        
        for (String rssUrl : rssUrls) {
            System.out.println("\n=== RSS URL 연결 테스트: " + rssUrl + " ===");
            
            try {
                URL url = new URL(rssUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; SmallTown-Crawler/1.0)");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(15000);
                
                int responseCode = connection.getResponseCode();
                String contentType = connection.getContentType();
                
                System.out.println("응답 코드: " + responseCode);
                System.out.println("Content-Type: " + contentType);
                
                if (responseCode == 200) {
                    // 첫 몇 줄만 읽어서 실제 RSS 내용인지 확인
                    BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                    System.out.println("첫 5줄:");
                    for (int i = 0; i < 5; i++) {
                        String line = reader.readLine();
                        if (line == null) break;
                        System.out.println("  " + line);
                    }
                    reader.close();
                }
                
                connection.disconnect();
                
            } catch (Exception e) {
                System.out.println("연결 실패: " + e.getMessage());
            }
        }
    }

    @Test
    @DisplayName("확실한 RSS 피드 연결 테스트")
    void 확실한_rss_피드_연결_테스트() {
        String[] knownRssUrls = {
            "https://feeds.feedburner.com/oreilly/radar",
            "https://github.blog/feed/",
            "https://techcrunch.com/feed/"
        };
        
        for (String rssUrl : knownRssUrls) {
            System.out.println("\n=== 확실한 RSS URL 테스트: " + rssUrl + " ===");
            
            try {
                URL url = new URL(rssUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; SmallTown-Crawler/1.0)");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(15000);
                
                int responseCode = connection.getResponseCode();
                String contentType = connection.getContentType();
                
                System.out.println("응답 코드: " + responseCode);
                System.out.println("Content-Type: " + contentType);
                
                if (responseCode == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                    System.out.println("첫 3줄:");
                    for (int i = 0; i < 3; i++) {
                        String line = reader.readLine();
                        if (line == null) break;
                        System.out.println("  " + line);
                    }
                    reader.close();
                }
                
                connection.disconnect();
                
            } catch (Exception e) {
                System.out.println("연결 실패: " + e.getMessage());
            }
        }
    }
}