package com.newcodes7.small_town.search.service;

import com.newcodes7.small_town.auth.entity.User;
import com.newcodes7.small_town.auth.repository.UserRepository;
import com.newcodes7.small_town.search.dto.SearchClickRequestDto;
import com.newcodes7.small_town.search.entity.SearchClickLog;
import com.newcodes7.small_town.search.entity.SearchLog;
import com.newcodes7.small_town.search.repository.SearchClickLogRepository;
import com.newcodes7.small_town.search.repository.SearchLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class SearchClickLogService {

    private final SearchClickLogRepository searchClickLogRepository;
    private final SearchLogRepository searchLogRepository;
    private final UserRepository userRepository;

    @Async
    @Transactional
    public void logClick(Long articleId, SearchClickRequestDto dto,
                         String username, String ipAddress, String userAgent) {
        try {
            User user = (username != null)
                    ? userRepository.findByUsernameAndDeletedAtIsNull(username).orElse(null)
                    : null;

            SearchLog searchLog = searchLogRepository
                    .findFirstBySearchKeywordOrderByCreatedAtDesc(dto.getSearchKeyword())
                    .orElse(null);

            SearchClickLog clickLog = SearchClickLog.builder()
                    .searchKeyword(dto.getSearchKeyword())
                    .searchLog(searchLog)
                    .articleId(articleId)
                    .clickPosition(dto.getClickPosition() != null ? dto.getClickPosition() : 0)
                    .pageNumber(dto.getPageNumber() != null ? dto.getPageNumber() : 0)
                    .pageSize(dto.getPageSize() != null ? dto.getPageSize() : 10)
                    .sortType(dto.getSortType() != null ? dto.getSortType() : "relevance")
                    .totalResults(dto.getTotalResults() != null ? dto.getTotalResults() : 0)
                    .bm25Score(dto.getBm25Score())
                    .vectorScore(dto.getVectorScore())
                    .finalScore(dto.getFinalScore())
                    .bm25Rank(dto.getBm25Rank())
                    .vectorRank(dto.getVectorRank())
                    .user(user)
                    .ipAddress(ipAddress)
                    .userAgent(userAgent)
                    .build();

            searchClickLogRepository.save(clickLog);
        } catch (Exception e) {
            log.error("검색 클릭 로그 저장 실패: keyword={}, articleId={}",
                    dto.getSearchKeyword(), articleId, e);
        }
    }
}
