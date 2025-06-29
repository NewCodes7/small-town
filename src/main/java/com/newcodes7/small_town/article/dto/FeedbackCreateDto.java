package com.newcodes7.small_town.article.dto;

import lombok.Data;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Data
public class FeedbackCreateDto {
    
    @Size(max = 100, message = "이름은 100자 이하로 입력해주세요")
    private String name;
    
    @Email(message = "올바른 이메일 형식을 입력해주세요")
    @Size(max = 255, message = "이메일은 255자 이하로 입력해주세요")
    private String email;
    
    @NotBlank(message = "피드백 유형을 선택해주세요")
    @Size(max = 50, message = "피드백 유형이 올바르지 않습니다")
    private String type;
    
    @NotBlank(message = "피드백 내용을 입력해주세요")
    @Size(min = 10, max = 2000, message = "피드백 내용은 10자 이상 2000자 이하로 입력해주세요")
    private String message;
}