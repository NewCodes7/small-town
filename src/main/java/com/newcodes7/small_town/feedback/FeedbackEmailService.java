package com.newcodes7.small_town.feedback;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackEmailService {

    private final JavaMailSender mailSender;

    @Value("${feedback.notification.email:}")
    private String notificationEmail;

    @Async
    public void notifyNewFeedback(Feedback feedback) {
        if (notificationEmail == null || notificationEmail.isBlank()) {
            log.debug("feedback.notification.email 미설정 — 피드백 알림 메일 발송 건너뜀");
            return;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(notificationEmail);
            message.setSubject("[Small Town] 새 피드백 접수 (%s)".formatted(feedback.getType()));
            message.setText("""
                유형: %s
                작성자: %s (%s)
                접수 시각: %s

                내용:
                %s
                """.formatted(
                    feedback.getType(),
                    feedback.getName(),
                    feedback.getEmail() != null ? feedback.getEmail() : "이메일 없음",
                    feedback.getCreatedAt(),
                    feedback.getMessage()
                ));
            mailSender.send(message);
        } catch (Exception e) {
            log.error("피드백 알림 메일 발송 실패. feedbackId={}", feedback.getId(), e);
        }
    }
}
