package com.newcodes7.small_town.term.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.newcodes7.small_town.global.entity.UserDictionary;

/**
 * UserDictionary Repository
 * 사용자 정의 단어 사전을 관리
 */
public interface UserDictionaryRepository extends JpaRepository<UserDictionary, Long> {

    /**
     * 단어로 UserDictionary 조회
     */
    Optional<UserDictionary> findByWord(String word);

    /**
     * 단어가 존재하는지 확인
     */
    boolean existsByWord(String word);

    /**
     * 모든 사용자 단어 목록 조회 (최신순)
     */
    @Query("SELECT u FROM UserDictionary u ORDER BY u.createdAt DESC")
    List<UserDictionary> findAllOrderByCreatedAtDesc();

    /**
     * DeepL 번역 항목만 조회 (최신순)
     */
    @Query("SELECT u FROM UserDictionary u WHERE u.reason LIKE 'DeepL translation of: %' ORDER BY u.createdAt DESC")
    List<UserDictionary> findDeeplTranslatedEntries();
}
