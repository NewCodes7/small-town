package com.newcodes7.small_town.admin.dto;

import java.time.LocalDateTime;

public record AdminUserListDto(
        Long id,
        String nickname,
        String email,
        String status,
        LocalDateTime lastLoginAt,
        LocalDateTime createdAt,
        LocalDateTime lastArticleViewAt
) {}
