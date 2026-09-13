package com.proxyviewer.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionPayloadTest {

    @Test
    void keepsPlainTextSubscriptions() {
        String plain = "vless://uuid@example.com:443?type=tcp#🇺🇸 US-01";

        assertThat(SubscriptionService.normalizeBody(plain)).isEqualTo(plain);
    }

    @Test
    void decodesBase64Subscriptions() {
        String plain = "vless://uuid@example.com:443?type=tcp#US-01\nvmess://abc";
        String encoded = Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));

        assertThat(SubscriptionService.normalizeBody(encoded)).isEqualTo(plain);
    }

    @Test
    void decodesUrlSafeBase64() {
        String plain = "vless://uuid@example.com:443?type=ws&path=/a?b=c#US-01~~";
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(plain.getBytes(StandardCharsets.UTF_8));

        assertThat(SubscriptionService.normalizeBody(encoded)).isEqualTo(plain);
    }

    @Test
    void returnsNullForUndecodablePayload() {
        assertThat(SubscriptionService.normalizeBody("!!!not-base64!!!")).isNull();
        assertThat(Base64Codec.decode(null)).isNull();
        assertThat(Base64Codec.decode("")).isNull();
    }

    @Test
    void decodesChunkedTransferEncoding() {
        String body = "4\r\nWiki\r\n5\r\npedia\r\n0\r\n\r\n";

        assertThat(SubscriptionService.decodeChunked(body)).isEqualTo("Wikipedia");
    }

    @Test
    void chunkedDecoderToleratesTruncatedInput() {
        // 数据块本身完整、只是缺少结束符：保留已解析出的内容
        assertThat(SubscriptionService.decodeChunked("4\r\nWiki")).isEqualTo("Wiki");
        // 非法块长度：直接放弃，不抛异常
        assertThat(SubscriptionService.decodeChunked("ZZ\r\nbad\r\n0\r\n")).isEmpty();
    }

    @Test
    void decodesStandardBase64WithWhitespace() {
        String plain = "vmess://eyJ2IjoiMiJ9";
        String encoded = Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));

        assertThat(SubscriptionService.normalizeBody(encoded)).isEqualTo(plain);
        assertThat(SubscriptionService.normalizeBody("  " + encoded + "\n")).isEqualTo(plain);
    }
}
