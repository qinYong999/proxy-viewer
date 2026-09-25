package com.proxyviewer.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 登录页。仅负责渲染表单，真正的认证由 Spring Security 的
 * {@code POST /login}（formLogin）完成，见 {@code SecurityConfig}。
 */
@Controller
public class LoginController {

    @GetMapping("/login")
    public String login(@RequestParam(value = "error", required = false) String error,
                        @RequestParam(value = "logout", required = false) String logout,
                        Model model) {
        if (error != null) {
            model.addAttribute("error", "用户名或口令不正确，请重试");
        }
        if (logout != null) {
            model.addAttribute("msg", "已退出登录");
        }
        return "login";
    }
}
