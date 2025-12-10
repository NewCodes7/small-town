package com.newcodes7.small_town.auth.controller;

import com.newcodes7.small_town.auth.dto.LoginRequestDto;
import com.newcodes7.small_town.auth.dto.SignupRequestDto;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/auth")
public class AuthWebController {
    
    @GetMapping("/login")
    public String loginPage(Model model,
                           @AuthenticationPrincipal UserDetails userDetails,
                           @org.springframework.web.bind.annotation.RequestParam(value = "redirect", required = false) String redirectUrl) {
        if (userDetails != null) {
            // 이미 로그인된 경우 redirect URL로 이동 (없으면 홈으로)
            if (redirectUrl != null && !redirectUrl.isEmpty()) {
                return "redirect:" + redirectUrl;
            }
            return "redirect:/";
        }
        model.addAttribute("loginRequest", new LoginRequestDto());
        model.addAttribute("redirectUrl", redirectUrl != null ? redirectUrl : "/");
        return "auth/login";
    }
    
    @GetMapping("/signup")
    public String signupPage(Model model, @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails != null) {
            return "redirect:/";
        }
        model.addAttribute("signupRequest", new SignupRequestDto());
        return "auth/signup";
    }
    
    @GetMapping("/profile")
    public String profilePage(Model model, @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null) {
            return "redirect:/auth/login";
        }
        model.addAttribute("user", userDetails);
        return "auth/profile";
    }
}