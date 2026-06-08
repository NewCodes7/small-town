package com.newcodes7.small_town.admin.service;

import com.newcodes7.small_town.admin.dto.ArticleClickLogDto;
import com.newcodes7.small_town.activity.entity.ArticleClickLog;
import com.newcodes7.small_town.activity.entity.ReferralSource;
import com.newcodes7.small_town.activity.repository.ArticleClickLogRepository;
import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.global.entity.Article;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminArticleClickService {

    private final ArticleClickLogRepository articleClickLogRepository;
    private final ArticleRepository articleRepository;

    public Page<ArticleClickLogDto> getClickLogs(String source, int page, int size) {
        ReferralSource src = parseSource(source);
        Page<ArticleClickLog> logs = articleClickLogRepository.findAllWithFilter(
                src, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));

        List<Long> articleIds = logs.getContent().stream()
                .map(ArticleClickLog::getArticleId)
                .distinct()
                .toList();

        Map<Long, String> titleMap = articleRepository.findAllByIdIn(articleIds).stream()
                .collect(Collectors.toMap(Article::getId, Article::getTitle));

        return logs.map(log -> ArticleClickLogDto.from(log, titleMap.get(log.getArticleId())));
    }

    /** 최근 7일 유입경로(source)별 클릭 수 */
    public List<Object[]> getSourceBreakdown() {
        return articleClickLogRepository.countBySourceLast7Days();
    }

    public long getTotalCount() {
        return articleClickLogRepository.countAll();
    }

    public long getTodayCount() {
        return articleClickLogRepository.countToday();
    }

    /** 선택 가능한 전체 source 목록 (필터 드롭다운용) */
    public ReferralSource[] getAllSources() {
        return ReferralSource.values();
    }

    private ReferralSource parseSource(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        try {
            return ReferralSource.valueOf(source.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
