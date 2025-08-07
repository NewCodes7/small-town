package com.newcodes7.small_town.global.exception;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@Primary
public class CustomErrorController {

    @RequestMapping("/error")
    public String handleError(HttpServletRequest request, Model model) {
        Object statusCode = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        Object uri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);

        model.addAttribute("status", statusCode);
        model.addAttribute("path", uri);
        model.addAttribute("error", "페이지를 찾을 수 없습니다");
        model.addAttribute("message", "요청하신 페이지가 존재하지 않습니다.");

        return "error/404"; 
    }
}