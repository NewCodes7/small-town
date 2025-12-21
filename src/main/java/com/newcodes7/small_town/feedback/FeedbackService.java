package com.newcodes7.small_town.feedback;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.newcodes7.small_town.auth.entity.User;
import com.newcodes7.small_town.auth.repository.UserRepository;
import com.newcodes7.small_town.feedback.Feedback.FeedbackStatus;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FeedbackService {

    private final FeedbackRepository feedbackRepository;
    private final UserRepository userRepository;
    
    // 허용된 피드백 타입 목록
    private static final List<String> ALLOWED_TYPES = Arrays.asList(
        "기능개선", "버그리포트", "새기능", "UI/UX", "기타"
    );
    
    @Transactional
    public FeedbackResponseDto createFeedback(FeedbackCreateDto dto, String ipAddress, String userAgent, String userEmail) {
        // 입력 검증
        validateFeedbackCreate(dto);

        // 스팸 방지: 같은 IP에서 1시간 내에 5개 이상 피드백 제출 방지
        long recentFeedbacks = feedbackRepository.countByIpAddressAndCreatedAtAfter(
            ipAddress, LocalDateTime.now().minusHours(1));

        if (recentFeedbacks >= 5) {
            throw new RuntimeException("너무 많은 피드백을 제출했습니다. 1시간 후에 다시 시도해주세요.");
        }

        // 로그인한 사용자인 경우 User 엔티티 조회
        User user = null;
        if (userEmail != null) {
            user = userRepository.findByUsernameAndDeletedAtIsNull(userEmail).orElse(null);
        }

        // 피드백 생성
        Feedback feedback = Feedback.builder()
            .user(user)
            .name(dto.getName() != null && !dto.getName().trim().isEmpty() ? dto.getName().trim() : "익명")
            .email(dto.getEmail() != null && !dto.getEmail().trim().isEmpty() ? dto.getEmail().trim() : null)
            .type(dto.getType().trim())
            .message(dto.getMessage().trim())
            .ipAddress(ipAddress)
            .userAgent(userAgent)
            .status(Feedback.FeedbackStatus.PENDING)
            .build();

        Feedback savedFeedback = feedbackRepository.save(feedback);

        log.info("새로운 피드백이 접수되었습니다. ID: {}, 유형: {}, 이름: {}, 사용자: {}",
            savedFeedback.getId(), savedFeedback.getType(), savedFeedback.getName(),
            user != null ? user.getEmail() : "비로그인");

        return FeedbackResponseDto.from(savedFeedback);
    }
    
    public Page<FeedbackResponseDto> getAllFeedbacks(Pageable pageable) {
        return feedbackRepository.findAllByOrderByCreatedAtDesc(pageable)
            .map(FeedbackResponseDto::from);
    }
    
    public Page<FeedbackResponseDto> getFeedbacksByStatus(Feedback.FeedbackStatus status, Pageable pageable) {
        return feedbackRepository.findByStatusOrderByCreatedAtDesc(status, pageable)
            .map(FeedbackResponseDto::from);
    }
    
    public Page<FeedbackResponseDto> getFeedbacksByType(String type, Pageable pageable) {
        if (!ALLOWED_TYPES.contains(type)) {
            throw new RuntimeException("허용되지 않은 피드백 타입입니다: " + type);
        }
        
        return feedbackRepository.findByTypeOrderByCreatedAtDesc(type, pageable)
            .map(FeedbackResponseDto::from);
    }
    
    @Transactional
    public FeedbackResponseDto updateFeedbackStatus(Long feedbackId, Feedback.FeedbackStatus status) {
        Feedback feedback = feedbackRepository.findById(feedbackId)
            .orElseThrow(() -> new RuntimeException("존재하지 않는 피드백입니다. ID: " + feedbackId));
        
        feedback.setStatus(status);
        Feedback updatedFeedback = feedbackRepository.save(feedback);
        
        log.info("피드백 상태가 변경되었습니다. ID: {}, 새 상태: {}", feedbackId, status);
        
        return FeedbackResponseDto.from(updatedFeedback);
    }
    
    public long getTotalFeedbackCount() {
        return feedbackRepository.count();
    }
    
    public long getRecentFeedbackCount() {
        return feedbackRepository.countRecentFeedbacks(LocalDateTime.now().minusDays(1));
    }
    
    private void validateFeedbackCreate(FeedbackCreateDto dto) {
        if (dto == null) {
            throw new RuntimeException("피드백 데이터가 비어있습니다.");
        }
        
        if (dto.getType() == null || dto.getType().trim().isEmpty()) {
            throw new RuntimeException("피드백 유형을 선택해주세요.");
        }
        
        if (!ALLOWED_TYPES.contains(dto.getType().trim())) {
            throw new RuntimeException("허용되지 않은 피드백 유형입니다: " + dto.getType());
        }
        
        if (dto.getMessage() == null || dto.getMessage().trim().isEmpty()) {
            throw new RuntimeException("피드백 내용을 입력해주세요.");
        }
        
        if (dto.getMessage().trim().length() < 10) {
            throw new RuntimeException("피드백 내용은 10자 이상 입력해주세요.");
        }
        
        if (dto.getMessage().trim().length() > 2000) {
            throw new RuntimeException("피드백 내용은 2000자 이하로 입력해주세요.");
        }
        
        // 이메일 검증 (제공된 경우)
        if (dto.getEmail() != null && !dto.getEmail().trim().isEmpty()) {
            String email = dto.getEmail().trim();
            if (!email.matches("^[A-Za-z0-9+_.-]+@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})$")) {
                throw new RuntimeException("올바른 이메일 형식을 입력해주세요: " + email);
            }
        }
        
        // 이름 길이 검증 (제공된 경우)
        if (dto.getName() != null && dto.getName().trim().length() > 100) {
            throw new RuntimeException("이름은 100자 이하로 입력해주세요.");
        }
    }

    public Page<Feedback> getFeedbacks(FeedbackStatus status, String type, 
                                        int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return feedbackRepository.findFeedbacks(status, type, pageable);
    }
}