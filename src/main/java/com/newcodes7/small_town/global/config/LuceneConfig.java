package com.newcodes7.small_town.global.config;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.newcodes7.small_town.term.repository.StopwordRepository;
import com.newcodes7.small_town.term.repository.UserDictionaryRepository;
import com.newcodes7.small_town.global.entity.UserDictionary;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Apache Lucene EnglishAnalyzer 설정
 * 영어 형태소 분석을 위한 Bean 설정
 */
@Configuration
@Slf4j
@RequiredArgsConstructor
public class LuceneConfig {

    private final StopwordRepository stopwordRepository;
    private final UserDictionaryRepository userDictionaryRepository;

    /**
     * EnglishAnalyzer Bean
     * - 기본 영어 불용어 포함
     * - DB에서 불용어 추가 로드
     */
    @Bean
    public EnglishAnalyzer englishAnalyzer() {
        log.info("Lucene EnglishAnalyzer 초기화 시작");
        long startTime = System.currentTimeMillis();

        // 기본 영어 불용어 세트 복사
        CharArraySet stopwords = new CharArraySet(EnglishAnalyzer.getDefaultStopSet(), true);

        // DB에서 영어 불용어 추가
        try {
            Set<String> dbStopwords = stopwordRepository.findAllTerms();
            for (String word : dbStopwords) {
                if (isEnglish(word)) {
                    stopwords.add(word.toLowerCase());
                }
            }
            log.info("DB에서 영어 불용어 {} 개 로드", dbStopwords.size());
        } catch (Exception e) {
            log.warn("DB 불용어 로드 실패: {}", e.getMessage());
        }

        EnglishAnalyzer analyzer = new EnglishAnalyzer(stopwords);

        long endTime = System.currentTimeMillis();
        log.info("Lucene EnglishAnalyzer 초기화 완료 (소요 시간: {}ms)", endTime - startTime);

        return analyzer;
    }

    /**
     * 영어 기술 용어 사전 (EN_TECH 품사 태그가 붙은 단어들)
     * UserDictionary에서 EN_TECH 품사 태그를 가진 단어들을 로드
     */
    @Bean
    public Set<String> englishTechTerms() {
        Set<String> techTerms = new HashSet<>();

        try {
            List<UserDictionary> dictionaries = userDictionaryRepository.findAllOrderByCreatedAtDesc();
            for (UserDictionary dict : dictionaries) {
                String posTag = dict.getPosTag();
                // EN_TECH 태그이거나 영어 단어인 경우
                if ("EN_TECH".equals(posTag) ||
                    ("SL".equals(posTag) && isEnglish(dict.getWord()))) {
                    techTerms.add(dict.getWord().toLowerCase());
                }
            }
            log.info("영어 기술 용어 사전 로드 완료: {} 개", techTerms.size());
        } catch (Exception e) {
            log.warn("영어 기술 용어 사전 로드 실패: {}", e.getMessage());
        }

        return techTerms;
    }

    /**
     * 문자열이 영어로만 구성되어 있는지 확인
     */
    private boolean isEnglish(String word) {
        if (word == null || word.isEmpty()) {
            return false;
        }
        for (char c : word.toCharArray()) {
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ||
                  c == '-' || c == '_' || c == '.' || Character.isDigit(c))) {
                return false;
            }
        }
        return true;
    }
}
