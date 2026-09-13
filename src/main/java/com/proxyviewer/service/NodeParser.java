package com.proxyviewer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.proxyviewer.model.ProxyNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 订阅节点解析器：把一行 {@code vless://...} / {@code vmess://...} 文本解析为 {@link ProxyNode}。
 *
 * <p>与本类相关的两个历史缺陷已修复：</p>
 * <ol>
 *   <li>国家识别不再用 {@code name.contains(emoji)} 逐个试，而且不再把 emoji 写进 countryCode 字段；
 *       改为扫描"区域指示符"成对出现的国旗 emoji，写出真正的两字母代码。</li>
 *   <li>两字母回退匹配要求小写字母边界 + 分隔符，且必须命中国家表，避免 🏁 被误判成 "AY"。</li>
 * </ol>
 */
public class NodeParser {

    private static final Logger log = LoggerFactory.getLogger(NodeParser.class);

    private static final int REGIONAL_INDICATOR_A = 0x1F1E6;

    /** 2 字母代码 → 中文名称 */
    private static final Map<String, String> CODE_TO_NAME = new LinkedHashMap<>();

    /** 国旗 emoji → 2 字母代码（由 CODE_TO_NAME 反推，保证两者永不脱节） */
    private static final Map<String, String> FLAG_TO_CODE = new LinkedHashMap<>();

    /**
     * 两字母代码回退：前面不能是字母，后面必须是分隔符或结束。
     * 这样 "AY"(🏁 的历史误判)、"HK01" 之类都不会命中。
     */
    private static final Pattern CODE_PATTERN =
            Pattern.compile("(?<![A-Za-z])([A-Z]{2})(?=[\\-_ .|/]|$)");

    static {
        CODE_TO_NAME.put("AE", "阿联酋");
        CODE_TO_NAME.put("AR", "阿根廷");
        CODE_TO_NAME.put("AT", "奥地利");
        CODE_TO_NAME.put("AU", "澳大利亚");
        CODE_TO_NAME.put("BE", "比利时");
        CODE_TO_NAME.put("BG", "保加利亚");
        CODE_TO_NAME.put("BR", "巴西");
        CODE_TO_NAME.put("CA", "加拿大");
        CODE_TO_NAME.put("CH", "瑞士");
        CODE_TO_NAME.put("CL", "智利");
        CODE_TO_NAME.put("CN", "中国");
        CODE_TO_NAME.put("CO", "哥伦比亚");
        CODE_TO_NAME.put("CZ", "捷克");
        CODE_TO_NAME.put("DE", "德国");
        CODE_TO_NAME.put("DK", "丹麦");
        CODE_TO_NAME.put("EE", "爱沙尼亚");
        CODE_TO_NAME.put("EG", "埃及");
        CODE_TO_NAME.put("ES", "西班牙");
        CODE_TO_NAME.put("EU", "欧盟");
        CODE_TO_NAME.put("FI", "芬兰");
        CODE_TO_NAME.put("FR", "法国");
        CODE_TO_NAME.put("GB", "英国");
        CODE_TO_NAME.put("GR", "希腊");
        CODE_TO_NAME.put("HK", "香港");
        CODE_TO_NAME.put("HR", "克罗地亚");
        CODE_TO_NAME.put("HU", "匈牙利");
        CODE_TO_NAME.put("ID", "印度尼西亚");
        CODE_TO_NAME.put("IE", "爱尔兰");
        CODE_TO_NAME.put("IL", "以色列");
        CODE_TO_NAME.put("IN", "印度");
        CODE_TO_NAME.put("IQ", "伊拉克");
        CODE_TO_NAME.put("IR", "伊朗");
        CODE_TO_NAME.put("IS", "冰岛");
        CODE_TO_NAME.put("IT", "意大利");
        CODE_TO_NAME.put("JP", "日本");
        CODE_TO_NAME.put("KR", "韩国");
        CODE_TO_NAME.put("KZ", "哈萨克斯坦");
        CODE_TO_NAME.put("LT", "立陶宛");
        CODE_TO_NAME.put("LU", "卢森堡");
        CODE_TO_NAME.put("LV", "拉脱维亚");
        CODE_TO_NAME.put("MD", "摩尔多瓦");
        CODE_TO_NAME.put("MO", "澳门");
        CODE_TO_NAME.put("MX", "墨西哥");
        CODE_TO_NAME.put("MY", "马来西亚");
        CODE_TO_NAME.put("NG", "尼日利亚");
        CODE_TO_NAME.put("NL", "荷兰");
        CODE_TO_NAME.put("NO", "挪威");
        CODE_TO_NAME.put("NZ", "新西兰");
        CODE_TO_NAME.put("PA", "巴拿马");
        CODE_TO_NAME.put("PE", "秘鲁");
        CODE_TO_NAME.put("PH", "菲律宾");
        CODE_TO_NAME.put("PK", "巴基斯坦");
        CODE_TO_NAME.put("PL", "波兰");
        CODE_TO_NAME.put("PT", "葡萄牙");
        CODE_TO_NAME.put("RO", "罗马尼亚");
        CODE_TO_NAME.put("RS", "塞尔维亚");
        CODE_TO_NAME.put("RU", "俄罗斯");
        CODE_TO_NAME.put("SA", "沙特阿拉伯");
        CODE_TO_NAME.put("SE", "瑞典");
        CODE_TO_NAME.put("SG", "新加坡");
        CODE_TO_NAME.put("SI", "斯洛文尼亚");
        CODE_TO_NAME.put("SK", "斯洛伐克");
        CODE_TO_NAME.put("TH", "泰国");
        CODE_TO_NAME.put("TR", "土耳其");
        CODE_TO_NAME.put("TW", "台湾");
        CODE_TO_NAME.put("UA", "乌克兰");
        CODE_TO_NAME.put("UK", "英国");
        CODE_TO_NAME.put("US", "美国");
        CODE_TO_NAME.put("UZ", "乌兹别克斯坦");
        CODE_TO_NAME.put("VN", "越南");
        CODE_TO_NAME.put("ZA", "南非");

        CODE_TO_NAME.forEach((code, name) -> FLAG_TO_CODE.put(flagOf(code), code));
    }

    private final ObjectMapper objectMapper;

    public NodeParser() {
        this(new ObjectMapper());
    }

    public NodeParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 解析一行订阅内容；不支持的协议返回 {@code null}（不抛异常） */
    public ProxyNode parse(String line) {
        if (line == null) {
            return null;
        }
        String trimmed = line.trim();
        if (trimmed.startsWith("vless://")) {
            try {
                return parseVless(trimmed);
            } catch (Exception e) {
                log.debug("VLESS 解析失败: {}", e.getMessage());
                return null;
            }
        }
        if (trimmed.startsWith("vmess://")) {
            try {
                return parseVmess(trimmed);
            } catch (Exception e) {
                log.debug("VMESS 解析失败: {}", e.getMessage());
                return null;
            }
        }
        return null;
    }

    // ======================== VLESS ========================

    ProxyNode parseVless(String uriStr) {
        String rest = uriStr.substring("vless://".length());

        String fragment = "";
        int hashIdx = rest.lastIndexOf('#');
        if (hashIdx >= 0) {
            fragment = urlDecode(rest.substring(hashIdx + 1));
            rest = rest.substring(0, hashIdx);
        }

        String query = "";
        int qIdx = rest.indexOf('?');
        if (qIdx >= 0) {
            query = rest.substring(qIdx + 1);
            rest = rest.substring(0, qIdx);
        }

        int atIdx = rest.indexOf('@');
        if (atIdx < 0) {
            return null;
        }
        String uuid = rest.substring(0, atIdx);

        String server;
        int port = 443;
        String hostPort = rest.substring(atIdx + 1);
        if (hostPort.startsWith("[")) {
            // IPv6 字面量: [2001:db8::1]:443
            int close = hostPort.indexOf(']');
            if (close < 0) {
                return null;
            }
            server = hostPort.substring(1, close);
            String tail = hostPort.substring(close + 1);
            if (tail.startsWith(":") && tail.length() > 1) {
                port = parseIntOr(tail.substring(1), 443);
            }
        } else if (hostPort.contains(":")) {
            String[] parts = hostPort.split(":", 2);
            server = parts[0];
            port = parseIntOr(parts[1], 443);
        } else {
            server = hostPort;
        }
        if (server.isBlank()) {
            return null;
        }

        Map<String, String> params = parseQueryParams(query);

        ProxyNode node = new ProxyNode();
        node.setProtocol("vless");
        node.setUuid(uuid);
        node.setServer(server);
        node.setPort(port);
        String security = params.getOrDefault("security", "");
        node.setSecurity(security);
        node.setNetwork(params.getOrDefault("type", "tcp"));
        node.setPath(params.getOrDefault("path", ""));
        node.setHost(params.getOrDefault("host", ""));
        // VLESS 用 security=tls 表达 TLS，没有独立的 tls 参数；
        // 这里归一化到 tls 字段，否则页面的 TLS 列对 VLESS 节点永远显示 "—"
        String tls = params.getOrDefault("tls", "");
        if (tls.isEmpty() && "tls".equalsIgnoreCase(security)) {
            tls = "tls";
        }
        node.setTls(tls);
        node.setSni(params.getOrDefault("sni", ""));
        node.setFp(params.getOrDefault("fp", ""));
        node.setAlpn(params.getOrDefault("alpn", ""));

        String displayName = fragment.isEmpty() ? (server + ":" + port) : fragment;
        // 二次解码处理双编码的节点名
        String secondDecode = tryDecode(displayName);
        if (secondDecode != null && !secondDecode.equals(displayName)) {
            displayName = secondDecode;
        }
        node.setName(displayName);
        parseCountryFromName(node, displayName);
        return node;
    }

    // ======================== VMESS ========================

    ProxyNode parseVmess(String uriStr) {
        String b64 = uriStr.substring("vmess://".length());
        String jsonStr = Base64Codec.decode(b64);
        if (jsonStr == null || jsonStr.isBlank()) {
            return null;
        }

        JsonNode json;
        try {
            json = objectMapper.readTree(jsonStr);
        } catch (Exception e) {
            return null;
        }
        if (json == null || !json.isObject()) {
            return null;
        }

        ProxyNode node = new ProxyNode();
        node.setProtocol("vmess");
        node.setServer(getText(json, "add"));
        node.setPort(parseIntOr(getText(json, "port"), 443));
        node.setUuid(getText(json, "id"));
        node.setAid(parseIntOr(getText(json, "aid"), 0));
        node.setNetwork(getText(json, "net"));
        node.setPath(getText(json, "path"));
        node.setHost(getText(json, "host"));
        node.setTls(getText(json, "tls"));
        node.setSecurity(getText(json, "security"));
        node.setSni(getText(json, "sni"));
        node.setFp(getText(json, "fp"));
        node.setAlpn(getText(json, "alpn"));
        if (json.has("skip-cert-verify") && !json.get("skip-cert-verify").isNull()) {
            node.setSkipCertVerify(json.get("skip-cert-verify").asBoolean(false));
        }

        if (node.getServer() == null || node.getServer().isBlank()) {
            return null;
        }

        String displayName = getText(json, "ps");
        if (displayName.isEmpty()) {
            displayName = getText(json, "name");
        }
        if (displayName.isEmpty()) {
            displayName = node.getServer() + ":" + node.getPort();
        }
        node.setName(displayName);
        parseCountryFromName(node, displayName);
        return node;
    }

    // ======================== 国家/地区识别 ========================

    /**
     * 按节点名推断国家/地区：
     * 1) 优先扫描国旗 emoji（两个相邻的区域指示符码点），写出真实两字母代码；
     * 2) 否则用"大写两字母 + 分隔符"回退，且必须命中国家表。
     */
    public void parseCountryFromName(ProxyNode node, String name) {
        if (node == null || name == null || name.isBlank()) {
            return;
        }

        String flagCode = findFlagCountryCode(name);
        if (flagCode != null) {
            setCountry(node, flagCode);
            return;
        }

        Matcher m = CODE_PATTERN.matcher(name);
        while (m.find()) {
            String code = m.group(1);
            if (CODE_TO_NAME.containsKey(code)) {
                setCountry(node, code);
                return;
            }
        }
    }

    /** 扫描字符串中"成对区域指示符"组成的国旗，返回对应国家代码；未命中返回 null */
    static String findFlagCountryCode(String text) {
        for (int i = 0; i < text.length(); i++) {
            int cp1 = text.codePointAt(i);
            int len1 = Character.charCount(cp1);
            if (!isRegionalIndicator(cp1)) {
                continue;
            }
            int j = i + len1;
            if (j >= text.length()) {
                break;
            }
            int cp2 = text.codePointAt(j);
            if (!isRegionalIndicator(cp2)) {
                continue;
            }
            String code = "" + (char) ('A' + cp1 - REGIONAL_INDICATOR_A)
                    + (char) ('A' + cp2 - REGIONAL_INDICATOR_A);
            if (CODE_TO_NAME.containsKey(code)) {
                return code;
            }
        }
        return null;
    }

    private static boolean isRegionalIndicator(int codePoint) {
        return codePoint >= REGIONAL_INDICATOR_A && codePoint <= REGIONAL_INDICATOR_A + 25;
    }

    private static void setCountry(ProxyNode node, String code) {
        node.setCountryCode(code);
        node.setCountryName(CODE_TO_NAME.get(code));
    }

    static String flagOf(String code) {
        return new String(Character.toChars(REGIONAL_INDICATOR_A + code.charAt(0) - 'A'))
                + new String(Character.toChars(REGIONAL_INDICATOR_A + code.charAt(1) - 'A'));
    }

    /** 供测试与模板判断国家表是否包含某代码 */
    public static boolean isKnownCountryCode(String code) {
        return code != null && CODE_TO_NAME.containsKey(code);
    }

    // ======================== 辅助 ========================

    static Map<String, String> parseQueryParams(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) {
            return params;
        }
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eqIdx = pair.indexOf('=');
            if (eqIdx > 0) {
                params.put(urlDecode(pair.substring(0, eqIdx)), urlDecode(pair.substring(eqIdx + 1)));
            } else {
                params.put(urlDecode(pair), "");
            }
        }
        return params;
    }

    private static String urlDecode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static String tryDecode(String s) {
        try {
            return urlDecode(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static int parseIntOr(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            int v = Integer.parseInt(value.trim());
            return v > 0 && v <= 65535 ? v : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String getText(JsonNode node, String field) {
        JsonNode f = node.get(field);
        return (f != null && !f.isNull()) ? f.asText() : "";
    }
}
