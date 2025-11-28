package com.newcodes7.small_town.global.config;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.newcodes7.small_town.article.repository.UserDictionaryRepository;
import com.newcodes7.small_town.global.entity.UserDictionary;

import kr.co.shineware.nlp.komoran.constant.DEFAULT_MODEL;
import kr.co.shineware.nlp.komoran.core.Komoran;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Configuration
@Slf4j
@RequiredArgsConstructor
public class KomoranConfig {

    private final UserDictionaryRepository userDictionaryRepository;

    /**
     * Komoran FULL 모델을 Bean으로 등록
     * FULL 모델은 더 많은 어휘와 높은 정확도를 제공
     *
     * 사용자 정의 사전을 적용하여 커스텀 단어 인식
     * Singleton으로 관리되어 메모리 효율적
     */
    @Bean
    public Komoran komoran() {
        log.info("Komoran FULL 모델 초기화 시작");
        long startTime = System.currentTimeMillis();

        Komoran komoran = new Komoran(DEFAULT_MODEL.FULL);

        // 사용자 정의 사전 적용
        try {
            applyUserDictionary(komoran);
        } catch (Exception e) {
            log.error("사용자 사전 적용 실패: {}", e.getMessage(), e);
        }

        long endTime = System.currentTimeMillis();
        log.info("Komoran FULL 모델 초기화 완료 (소요 시간: {}ms)", endTime - startTime);

        return komoran;
    }

    /**
     * 사용자 정의 사전을 Komoran에 적용
     * DB에서 UserDictionary를 읽어와 임시 파일로 저장한 후 Komoran에 적용
     */
    private void applyUserDictionary(Komoran komoran) throws IOException {
        List<UserDictionary> userDictionaries = userDictionaryRepository.findAllOrderByCreatedAtDesc();

        if (userDictionaries.isEmpty()) {
            log.info("사용자 정의 사전이 비어있습니다.");
            return;
        }

        // 임시 디렉토리에 사용자 사전 파일 생성
        Path userDicDir = Paths.get(System.getProperty("java.io.tmpdir"), "komoran");
        Files.createDirectories(userDicDir);

        File userDicFile = userDicDir.resolve("user_dictionary.txt").toFile();

        // 사용자 사전 파일 작성 (형식: 단어\t품사)
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(userDicFile))) {
            for (UserDictionary userDict : userDictionaries) {
                writer.write(userDict.getWord() + "\t" + userDict.getPosTag());
                writer.newLine();
            }
        }

        // Komoran에 사용자 사전 적용
        komoran.setUserDic(userDicFile.getAbsolutePath());

        log.info("사용자 정의 사전 적용 완료: {} 개의 단어", userDictionaries.size());
    }
}
