package com.proxyviewer.controller;

import com.proxyviewer.config.AppProperties;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * 给所有页面模板补充与认证相关的展示信息：
 * 认证是否开启、当前登录用户名（用于页面右上角的登录状态与退出按钮）。
 */
@ControllerAdvice
public class SecurityModelAdvice {

    private final boolean authEnabled;

    public SecurityModelAdvice(AppProperties props) {
        this.authEnabled = props.getSecurity().isEnabled();
    }

    @ModelAttribute("authEnabled")
    public boolean authEnabled() {
        return authEnabled;
    }

    @ModelAttribute("authUsername")
    public String authUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return "";
        }
        return authentication.getName();
    }
}
