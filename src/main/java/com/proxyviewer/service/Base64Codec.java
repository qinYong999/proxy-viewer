package com.proxyviewer.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Base64 解码工具：依次尝试标准 / URL-safe / MIME 三种变体 */
public final class Base64Codec {

    private Base64Codec() {
    }

    /** 解码失败返回 {@code null}（而非空串），便于调用方区分"失败"与"内容为空" */
    public static String decode(String b64) {
        if (b64 == null || b64.isEmpty()) {
            return null;
        }
        String cleaned = b64.trim().replace("\r", "").replace("\n", "");
        try {
            return new String(Base64.getDecoder().decode(cleaned), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            // 继续尝试其它变体
        }
        try {
            return new String(Base64.getUrlDecoder().decode(cleaned), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            // 继续
        }
        try {
            return new String(Base64.getMimeDecoder().decode(cleaned), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
