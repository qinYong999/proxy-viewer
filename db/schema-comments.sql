-- ============================================================
--  proxy_viewer 表注释 / 字段注释
--  由 db/_tools/gen_schema_comments.py 依据 information_schema 生成，请勿手工编辑；
--  注释文案在该脚本中维护，修改后重新生成即可。
--
--  执行方式（必须带 --default-character-set=utf8mb4，否则中文注释会乱码）：
--    mysql -h 127.0.0.1 -uroot -p --default-character-set=utf8mb4 < schema-comments.sql
--
--  说明：MODIFY COLUMN 的列定义取自当前库的真实结构（类型/字符集/NULL/默认值/自增），
--  仅追加 COMMENT，不改变字段本身，可重复执行。MySQL 没有「只改列注释」的语法，
--  因此每次执行都会重建这三张表的表定义（数据不丢失，大表请自行评估耗时）。
-- ============================================================

USE `proxy_viewer`;

-- ---------------- 表 proxy_nodes ----------------
ALTER TABLE `proxy_nodes`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键，自增',
  MODIFY COLUMN `aid` int NOT NULL COMMENT 'AlterID，VMESS 特有的额外混淆 ID；VLESS 节点恒为 0。VMess MD5 认证已废弃，写入内核时原样传递',
  MODIFY COLUMN `alpn` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT 'ALPN 协议协商列表，逗号分隔，如 h2,http/1.1；为空表示不指定',
  MODIFY COLUMN `country_code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '国家/地区两字母代码，由 NodeParser 从 node_name 推断（优先国旗 emoji，其次「大写两字母 + 分隔符」且须命中国家表）；未识别为 NULL，如 DE / KR',
  MODIFY COLUMN `country_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '国家/地区中文名称，与 country_code 一一对应（如 DE=德国）；未识别为 NULL。页面国家筛选与统计基于本字段',
  MODIFY COLUMN `fp` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT 'TLS 客户端指纹（uTLS fingerprint），写入 tlsSettings.fingerprint，用于模仿浏览器握手指纹：常见 chrome / firefox / safari / ios / android / edge / random',
  MODIFY COLUMN `host` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '伪装域名（WS/xhttp 的 Host、TCP+HTTP 伪装头的 Host），用于绕过域名封锁；生成内核配置时写入 wsSettings.host（Xray 26 起 host 已从 headers 提升为独立字段）',
  MODIFY COLUMN `node_name` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '节点显示名称，取自订阅条目（VLESS 的 #fragment / VMESS 的 ps 字段）；通常含国旗 emoji，国家识别即基于本字段，为空时回退为「地址:端口」',
  MODIFY COLUMN `network` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '传输层协议，来自 VLESS 的 type 参数 / VMESS 的 net 字段：常见 tcp / ws，另有 grpc / h2 / http；为空表示按 tcp 处理',
  MODIFY COLUMN `path` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '传输路径：WebSocket / xhttp / httpupgrade 的请求路径。节点测试时交给内核处理；不做 URL 解码以外的改写，避免破坏 %2F 这类被编码的路径',
  MODIFY COLUMN `port` int NOT NULL COMMENT '服务端口，取值 1-65535；解析不到时默认 443',
  MODIFY COLUMN `protocol` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '协议类型：vless / vmess（仅这两种会被解析入库）',
  MODIFY COLUMN `security` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '安全类型，来自订阅的 security 参数：none / tls / reality。生成内核配置时与 tls 字段合并归一化——任一为 reality 即按 REALITY 处理，任一为 tls 即按 TLS 处理',
  MODIFY COLUMN `server` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '服务器地址，域名或 IP（支持 IPv6 字面量，存储时不带方括号）',
  MODIFY COLUMN `skip_cert_verify` bit(1) NULL DEFAULT NULL COMMENT '是否跳过服务端证书校验（1 跳过 / 0 不跳过），来自 VMESS 的 skip-cert-verify；写入内核配置的 tlsSettings.allowInsecure。自签证书节点需要置 1，否则 TLS 握手会失败',
  MODIFY COLUMN `sni` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT 'TLS 握手的 SNI，写入内核配置的 tlsSettings.serverName；为空时回退为 host，再为空则用 server。Reality 节点同样使用本字段作为伪造的目标域名',
  MODIFY COLUMN `tls` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '是否启用 TLS，来自 VLESS 的 tls 参数 / VMESS 的 tls 字段，取值为 tls；为空或 none 表示明文。VLESS 会把 security=tls 归一化写入本字段，保证页面 TLS 列可用',
  MODIFY COLUMN `uuid_` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '用户身份凭据：VLESS 的 uuid / VMESS 的 id（等同代理凭据，注意保密）；列名带下划线是历史拼写，用于避开 uuid 关键字',
  MODIFY COLUMN `original_link` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '节点原始订阅链接（vless://... 或 vmess://...），用于页面「复制节点」直接粘贴到客户端；不参与节点指纹比对，刷新时会被订阅中的最新值覆盖',
  MODIFY COLUMN `last_test_result` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '最近一次测试结果：OK=真实可用；FAILED:原因=失败（如 FAILED:TIMEOUT、FAILED:REFUSED、FAILED:RESET、FAILED:HTTP_403，NO_CORE=未找到代理内核，REALITY_MISSING_PUBLIC_KEY=节点参数不足）；为空表示未测。页面「清理失败节点」按 FAILED: 前缀筛选',
  MODIFY COLUMN `last_test_time` datetime(6) NULL DEFAULT NULL COMMENT '最近一次测试时间；为空表示该节点从未被测试覆盖（如刚刷新入库尚未跑过测试）',
  MODIFY COLUMN `latency_ms` bigint NULL DEFAULT NULL COMMENT '最近一次真实延迟（毫秒）：为节点启动代理内核，经其 SOCKS 入站访问探测地址（默认 http://cp.cloudflare.com/generate_204，要求返回 204/200）实测往返耗时，连测 2 次取最小值。并非 TCP 建连耗时，因此能反映节点是否真的可用；-1 表示未测或不可达',
  MODIFY COLUMN `speed_kbps` bigint NULL DEFAULT NULL COMMENT '最近一次下载测速结果（Kbps = 字节数 × 8 ÷ 耗时毫秒数）；-1 表示未测速。仅对真实延迟通过的节点测速，限时 10 秒下载，到点即中断',
  MODIFY COLUMN `consecutive_failures` int NOT NULL DEFAULT '0' COMMENT '连续失败次数：测试成功清零，每次失败 +1；仅当 app.test.auto-delete-after-failures=N（N>0）且本值达到 N 时才自动删除节点，默认 0 表示只打标记、由用户手动清理',
  MODIFY COLUMN `encryption` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT 'VLESS 加密方式（分享链接参数 encryption），当前标准恒为 none；为空时按 none 处理',
  MODIFY COLUMN `flow` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT 'VLESS 流控模式（分享链接参数 flow），常见 xtls-rprx-vision。缺失会让 Reality/Vision 节点生成的内核配置必然握手失败，从而被误判为节点失效',
  MODIFY COLUMN `header_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT 'TCP 伪装头类型（分享链接参数 headerType / VMESS 的 type）：http 表示 TCP 上套 HTTP 伪装头，none 或为空表示裸 TCP',
  MODIFY COLUMN `public_key` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT 'REALITY 公钥（分享链接参数 pbk），写入 realitySettings.publicKey。缺失时测试会直接报 REALITY_MISSING_PUBLIC_KEY，而不是让内核神秘失败',
  MODIFY COLUMN `service_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT 'gRPC 传输的 serviceName，或 xhttp 传输的 mode（如 packet-up / stream-up / auto）',
  MODIFY COLUMN `short_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT 'REALITY shortId（分享链接参数 sid），写入 realitySettings.shortId；可为空串',
  MODIFY COLUMN `spider_x` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT 'REALITY spiderX（分享链接参数 spx），首包爬虫路径，写入 realitySettings.spiderX；为空时按 / 处理';

ALTER TABLE `proxy_nodes` COMMENT = '代理节点表：由订阅内容解析出的 VLESS / VMESS 节点明细，含最近一次真实可用性测试结果；行由增量对账维护（按节点指纹新增/更新/删除）';

-- ---------------- 表 node_test_records ----------------
ALTER TABLE `node_test_records`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键，自增',
  MODIFY COLUMN `deleted_count` int NULL DEFAULT NULL COMMENT '本批次因连续失败达到阈值而被自动删除的节点数；app.test.auto-delete-after-failures=0（默认）时恒为 0',
  MODIFY COLUMN `duration_ms` bigint NULL DEFAULT NULL COMMENT '本批次总耗时（毫秒），覆盖并发测试、结果批量落库与自动删除的完整过程',
  MODIFY COLUMN `failed_count` int NULL DEFAULT NULL COMMENT '本批次测试失败的节点数；失败只做标记，不等于被删除',
  MODIFY COLUMN `success_count` int NULL DEFAULT NULL COMMENT '本批次可达（测试成功）的节点数',
  MODIFY COLUMN `test_time` datetime(6) NOT NULL COMMENT '本批次测试的开始时间（同批次所有节点的 last_test_time 与本值一致）',
  MODIFY COLUMN `total_nodes` int NULL DEFAULT NULL COMMENT '本批次参与测试的节点总数（测试开始时库中全部节点数）',
  MODIFY COLUMN `trigger_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '触发方式：SCHEDULED=定时任务（默认每 6 小时）；MANUAL=页面手动触发',
  MODIFY COLUMN `avg_latency_ms` bigint NULL DEFAULT NULL COMMENT '本批次可达节点的平均真实延迟（毫秒），用于横向比较不同批次的节点池质量；无任何可达节点时为 -1',
  MODIFY COLUMN `speed_test_count` int NULL DEFAULT NULL COMMENT '本批次中实际完成下载测速（speed_kbps 有值）的节点数',
  MODIFY COLUMN `udp_success_count` int NULL DEFAULT NULL COMMENT '本批次中 UDP 可用（经 SOCKS5 UDP ASSOCIATE 发 NTP 探测并校验通过）的节点数；关闭 app.test.udp-test-enabled 时恒为 0';

ALTER TABLE `node_test_records` COMMENT = '节点测试批次表：每次真实可用性测试（定时或手动）产出一行汇总，用于测试历史页面展示节点池健康度趋势';

-- ---------------- 表 operation_logs ----------------
ALTER TABLE `operation_logs`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键，自增',
  MODIFY COLUMN `action` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '操作类型：REFRESH=刷新订阅；COPY=复制节点原始链接；DELETE=手动删除节点；PURGE=清理被标记为失败的节点',
  MODIFY COLUMN `client_ip` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '发起操作的客户端 IP；优先取反向代理头 X-Forwarded-For 的第一段，其次 X-Real-IP，最后为直连地址（本机访问即 127.0.0.1）',
  MODIFY COLUMN `created_at` datetime(6) NOT NULL COMMENT '操作发生时间，由应用服务器时间写入（非数据库时间）',
  MODIFY COLUMN `detail` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '操作描述：REFRESH 写入增量结果（形如「刷新订阅：新增 x / 更新 y / 删除 z」）；失败时写入失败原因；其余操作写入中文说明。供页面直接展示',
  MODIFY COLUMN `node_count` int NULL DEFAULT NULL COMMENT '本次操作涉及的节点数量：刷新为最终节点总数，复制/删除/清理为实际处理行数；刷新失败时为 0',
  MODIFY COLUMN `result` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '操作结果：SUCCESS=成功；FAILED=失败（目前仅刷新订阅可能失败）',
  MODIFY COLUMN `subscription_url` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '本次操作使用的订阅链接，仅刷新操作写入（记录完整地址，含订阅凭据，注意保密），其他操作与失败场景为 NULL';

ALTER TABLE `operation_logs` COMMENT = '操作日志表：记录刷新订阅、复制节点、删除节点、清理失败节点等关键操作，用于审计与问题回溯';
