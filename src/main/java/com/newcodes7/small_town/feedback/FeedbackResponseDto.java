package com.newcodes7.small_town.feedback;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class FeedbackResponseDto {
    
    private Long id;
    private String name;
    private String email;
    private String type;
    private String message;
    private String status;
    private String statusDescription;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    public static FeedbackResponseDto from(Feedback feedback) {
        FeedbackResponseDto dto = new FeedbackResponseDto();
        dto.setId(feedback.getId());
        dto.setName(feedback.getName());
        dto.setEmail(feedback.getEmail());
        dto.setType(feedback.getType());
        dto.setMessage(feedback.getMessage());
        dto.setStatus(feedback.getStatus().name());
        dto.setStatusDescription(feedback.getStatus().getDescription());
        dto.setCreatedAt(feedback.getCreatedAt());
        dto.setUpdatedAt(feedback.getUpdatedAt());
        return dto;
    }
}