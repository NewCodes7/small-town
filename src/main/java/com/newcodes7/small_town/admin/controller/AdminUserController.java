package com.newcodes7.small_town.admin.controller;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import com.newcodes7.small_town.auth.entity.User;
import com.newcodes7.small_town.auth.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Admin 회원 관리 Controller
 */
@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
@Slf4j
public class AdminUserController {

    private final UserRepository userRepository;

    /**
     * 회원 상세 정보 조회
     *
     * @param id 회원 ID
     * @return 회원 정보 (OAuth2 정보 포함)
     */
    @GetMapping("/users/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getUserDetail(@PathVariable Long id) {
        try {
            log.info("회원 상세 정보 조회 요청: {}", id);

            User user = userRepository.findByIdWithRoleAndProvider(id)
                    .orElseThrow(() -> new IllegalArgumentException("회원을 찾을 수 없습니다: " + id));

            Map<String, Object> response = new HashMap<>();

            // 기본 정보
            response.put("id", user.getId());
            response.put("email", user.getEmail());
            response.put("nickname", user.getNickname());
            response.put("profileImageUrl", user.getProfileImageUrl());
            response.put("status", user.getStatus().name());
            response.put("statusValue", user.getStatus().getValue());
            response.put("createdAt", user.getCreatedAt());
            response.put("lastLoginAt", user.getLastLoginAt());
            response.put("deletedAt", user.getDeletedAt());

            // 역할 정보
            if (user.getRole() != null) {
                response.put("roleName", user.getRole().getName());
            } else {
                response.put("roleName", "USER");
            }

            // Provider 정보
            if (user.getProvider() != null) {
                response.put("providerName", user.getProvider().getName());
            } else {
                response.put("providerName", "LOCAL");
            }

            // OAuth2 추가 정보
            response.put("oauthUsername", user.getOauthUsername());
            response.put("bio", user.getBio());
            response.put("blogUrl", user.getBlogUrl());
            response.put("company", user.getCompany());
            response.put("location", user.getLocation());
            response.put("profileUrl", user.getProfileUrl());
            response.put("publicRepos", user.getPublicRepos());
            response.put("followers", user.getFollowers());
            response.put("following", user.getFollowing());
            response.put("twitterUsername", user.getTwitterUsername());
            response.put("hireable", user.getHireable());

            log.debug("회원 정보 조회 완료: {} ({})", user.getEmail(), user.getId());

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            log.warn("회원 조회 실패: {}", e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);

        } catch (Exception e) {
            log.error("회원 상세 정보 조회 중 오류 발생: {}", id, e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "회원 정보 조회 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }
}
