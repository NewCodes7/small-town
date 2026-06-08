package com.newcodes7.small_town.admin.service;

import com.newcodes7.small_town.admin.dto.SearchClickLogDto;
import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.search.entity.SearchClickLog;
import com.newcodes7.small_town.search.repository.SearchClickLogRepository;
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
public class AdminSearchClickService {

    private final SearchClickLogRepository searchClickLogRepository;
    private final ArticleRepository articleRepository;

    public Page<SearchClickLogDto> getClickLogs(String keyword, int page, int size) {
        String kw = (keyword != null && !keyword.isBlank()) ? keyword : null;
        Page<SearchClickLog> logs = searchClickLogRepository.findAllWithFilter(
                kw, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));

        List<Long> articleIds = logs.getContent().stream()
                .map(SearchClickLog::getArticleId)
                .distinct()
                .toList();

        Map<Long, String> titleMap = articleRepository.findAllByIdIn(articleIds).stream()
                .collect(Collectors.toMap(Article::getId, Article::getTitle));

        return logs.map(log -> SearchClickLogDto.from(log, titleMap.get(log.getArticleId())));
    }

    public List<Object[]> getTopKeywords(int limit) {
        return searchClickLogRepository.findTopKeywordsLast7Days(limit);
    }

    public long getTotalCount() {
        return searchClickLogRepository.countAll();
    }

    public long getTodayCount() {
        return searchClickLogRepository.countToday();
    }

    public long getDistinctKeywordCount() {
        return searchClickLogRepository.countDistinctKeywords();
    }
}
