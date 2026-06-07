package com.newcodes7.small_town.admin.controller;

import com.newcodes7.small_town.admin.service.AdminUserActivityService;
import com.newcodes7.small_town.auth.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/admin/user-activity")
@RequiredArgsConstructor
public class AdminUserActivityController {

    private final AdminUserActivityService adminUserActivityService;

    @GetMapping
    public String list(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Model model) {
        Page<User> users = adminUserActivityService.getUsers(search, page, size);
        model.addAttribute("users", users);
        model.addAttribute("search", search);
        return "admin/user-activity/list";
    }

    @GetMapping("/{userId}")
    public String userDetail(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "articles") String tab,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Model model) {
        User user = adminUserActivityService.getUser(userId);
        model.addAttribute("user", user);
        model.addAttribute("currentTab", tab);

        switch (tab) {
            case "videos" -> model.addAttribute("videoViews",
                    adminUserActivityService.getVideoViewsByUser(userId, page, size));
            case "searches" -> model.addAttribute("searches",
                    adminUserActivityService.getSearchLogsByUser(userId, page, size));
            default -> model.addAttribute("articleViews",
                    adminUserActivityService.getArticleViewsByUser(userId, page, size));
        }

        return "admin/user-activity/detail";
    }

    @GetMapping("/ip/{ipAddress}")
    public String ipDetail(
            @PathVariable String ipAddress,
            @RequestParam(defaultValue = "articles") String tab,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Model model) {
        model.addAttribute("ipAddress", ipAddress);
        model.addAttribute("currentTab", tab);

        switch (tab) {
            case "videos" -> model.addAttribute("videoViews",
                    adminUserActivityService.getVideoViewsByIp(ipAddress, page, size));
            case "searches" -> model.addAttribute("searches",
                    adminUserActivityService.getSearchLogsByIp(ipAddress, page, size));
            default -> model.addAttribute("articleViews",
                    adminUserActivityService.getArticleViewsByIp(ipAddress, page, size));
        }

        return "admin/user-activity/ip-detail";
    }
}
