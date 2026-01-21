package com.newcodes7.small_town.global.exception;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
public class CustomErrorController implements ErrorController {

    @RequestMapping("/error")
    public String handleError(HttpServletRequest request, HttpServletResponse response, Model model) {
        Object statusCodeObj = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        Object uri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);

        int statusCode = 500; // default
        if (statusCodeObj != null) {
            statusCode = Integer.parseInt(statusCodeObj.toString());
        }

        // Set the response status code properly
        response.setStatus(statusCode);

        model.addAttribute("status", statusCode);
        model.addAttribute("path", uri);

        // Return appropriate error page based on status code
        if (statusCode == HttpStatus.NOT_FOUND.value()) {
            model.addAttribute("error", "페이지를 찾을 수 없습니다");
            model.addAttribute("message", "요청하신 페이지가 존재하지 않습니다.");
            return "error/404";
        } else if (statusCode == HttpStatus.INTERNAL_SERVER_ERROR.value()) {
            model.addAttribute("error", "서버 오류");
            model.addAttribute("message", "일시적인 서버 오류가 발생했습니다. 잠시 후 다시 시도해주세요.");
            return "error/500";
        } else if (statusCode == HttpStatus.FORBIDDEN.value()) {
            model.addAttribute("error", "접근 권한 없음");
            model.addAttribute("message", "이 페이지에 접근할 권한이 없습니다.");
            return "error/404"; // Using 404 as fallback
        } else {
            // For other errors, use 404 as default
            model.addAttribute("error", "페이지를 찾을 수 없습니다");
            model.addAttribute("message", "요청하신 페이지가 존재하지 않습니다.");
            return "error/404";
        }
    }
}