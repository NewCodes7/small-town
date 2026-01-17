package com.newcodes7.small_town.theme.controller;

import com.newcodes7.small_town.global.util.Client;
import com.newcodes7.small_town.theme.service.ThemeViewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
public class ThemeViewController {

    private final ThemeViewService themeViewService;

    @PostMapping("/api/themes/{themeId}/view")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> incrementViewCount(@PathVariable Long themeId,
                                                                   @AuthenticationPrincipal UserDetails userDetails,
                                                                   HttpServletRequest request) {
        String ipAddress = Client.getClientIpAddress(request);
        boolean incremented;

        if (userDetails != null) {
            // 인증된 사용자
            incremented = themeViewService.incrementViewCount(themeId, userDetails.getUsername(), ipAddress);
        } else {
            // 익명 사용자 (IP 기반)
            incremented = themeViewService.incrementViewCountByIp(themeId, ipAddress);
        }

        long viewCount = themeViewService.getViewCount(themeId);

        Map<String, Object> response = new HashMap<>();
        response.put("incremented", incremented);
        response.put("authenticated", userDetails != null);

        return ResponseEntity.ok(response);
    }
}
