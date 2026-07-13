package com.newcodes7.small_town.admin.service;

import com.newcodes7.small_town.search.entity.RagQueryLog;
import com.newcodes7.small_town.search.repository.RagQueryLogRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminRagHistoryService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final RagQueryLogRepository ragQueryLogRepository;

    public Page<RagQueryLog> getLogs(
            RagQueryLog.Outcome outcome,
            String model,
            LocalDateTime from,
            LocalDateTime to,
            String keyword,
            Long corporationId,
            int page,
            int size) {
        return ragQueryLogRepository.findAllWithFilter(
                outcome != null ? outcome.name() : null,
                (model != null && !model.isBlank()) ? model : null,
                from,
                to,
                (keyword != null && !keyword.isBlank()) ? keyword.trim() : null,
                corporationId != null ? String.valueOf(corporationId) : null,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    public RagQueryLog getDetail(Long id) {
        return ragQueryLogRepository
                .findById(id)
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "로그를 찾을 수 없습니다: " + id));
    }

    public long getTotalCount() {
        return ragQueryLogRepository.count();
    }

    public long getTodayCount() {
        return ragQueryLogRepository.countByCreatedAtAfter(todayStart());
    }

    public long getTodayErrorCount() {
        return ragQueryLogRepository.countByOutcomeAndCreatedAtAfter(
                RagQueryLog.Outcome.ERROR, todayStart());
    }

    private LocalDateTime todayStart() {
        return LocalDate.now(KST).atStartOfDay();
    }
}
