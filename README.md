# Proxy Subscription Viewer

代理订阅节点管理面板 —— 从订阅链接抓取节点配置，解析后存入 MySQL，通过 Web 页面浏览、筛选、测速、复制和清理。

定位是**个人自用的订阅聚合与可用性看门狗**：它不转发流量，只负责把订阅里的节点整理成可读、可测、可复制的一览表。

## 功能概览

- 🔄 **订阅抓取** — base64 或明文订阅均可，自动识别；传输逐级降级（配置的代理候选 → 直连 → DoH + 裸 TLS 直连）应对被污染的本地 DNS；SOCKS5 代理由自研实现，主机名交代理侧解析
- 📦 **增量入库** — 按节点指纹做差集（新增/更新/删除），未变化节点保留主键与测速结果，订阅内重复条目自动去重
- 📋 **节点展示** — 表格展示地址、端口、协议、传输、TLS、路径、SNI、Host，支持 20/50/100/200 分页与跳页
- 🔍 **国家筛选** — 按国家/地区筛选，统计数据（总数/VLESS/VMESS/WS/TCP/可达/失败）同步更新
- ↕️ **延迟排序** — 点击"延迟"列在 升序 → 降序 → 默认 之间循环
- ☑️ **多选复制** — 勾选节点一键复制原始订阅链接（`vless://` / `vmess://`），兼容 v2rayN、Shadowrocket、Clash Meta
- 🔬 **连通性测试** — 定时（默认 6 小时）+ 手动触发，TLS/WS 节点做真实下载测速并记录延迟
- 🧹 **失败节点管理** — 测试失败只打标记不删数据，页面可一键清理失败节点
- 📊 **审计与历史** — 操作日志（刷新/复制/删除/清理）与测试批次记录
- 🔐 **访问控制** — Spring Security 表单登录（`/login`）+ CSRF Token 防护，随机口令兜底，默认只监听 127.0.0.1

## 技术栈

| 层级 | 技术 |
|------|------|
| 语言 | Java 21+ |
| 框架 | Spring Boot 4.1.1 |
| ORM | Spring Data JPA + Hibernate |
| 数据库 | MySQL 8.0（测试用 H2 内存库） |
| 模板引擎 | Thymeleaf |
| 安全 | Spring Security（表单登录 + 会话 + CSRF Token） |
| 前端 | 原生 HTML/CSS/JS（无构建步骤） |
| 构建 | Maven 3.9+ |
| HTTP 客户端 | `java.net.http.HttpClient`（HTTP 代理与直连）+ 自研 `Socks5HttpClient`（SOCKS5，含 TLS）+ 裸 `SSLSocket`（DoH 降级通道） |
| 测试 | JUnit 6 + AssertJ + MockMvc |

> **Spring Boot 4.1.1 迁移要点**（2026-09 从 3.4.1 升级）：
> - Web starter 更名为 `spring-boot-starter-webmvc`（旧的 `spring-boot-starter-web` 已废弃，仍可用）；
> - 默认 JSON 库改为 Jackson 3：坐标 `com.fasterxml.jackson.core:jackson-databind` →
>   `tools.jackson.core:jackson-databind`，代码包名 `com.fasterxml.jackson.databind.*` → `tools.jackson.databind.*`；
> - `WebServerApplicationContext` 迁到 `org.springframework.boot.web.server.context`；
> - Web MVC 的测试自动配置被拆到独立模块 `spring-boot-webmvc-test`（不再随 `spring-boot-starter-test` 传递），
>   `@AutoConfigureMockMvc` 包名变为 `org.springframework.boot.webmvc.test.autoconfigure`；
> - 会话跟踪模式固定为 Cookie（`server.servlet.session.tracking-modes=cookie`）：容器一旦把
>   `;jsessionid=…` 拼进重定向 URL，该路径不会命中 `@GetMapping("/")`，会被欢迎页映射用空模型渲染 `index` 而报错；
> - 运行期基线：Java 21+、Tomcat 11、Hibernate 7、MySQL 驱动 9.x。

## 项目结构

```
proxy-viewer/
├── pom.xml
├── README.md
├── docs/
│   ├── v2rayn-testing-reference.md                  v2rayN 节点测试实现调研（重构依据）
│   └── v2rayn-speedtest-udptest-findings.md          v2rayN 测速/UDP 测试细节补充
├── db/
│   ├── schema-comments.sql                          表注释 + 字段注释（MySQL，可重复执行）
│   └── _tools/
│       ├── gen_schema_comments.py                   依据 information_schema 生成上述 SQL
│       ├── verify_schema_comments.py                克隆表试跑 + 逐列比对校验
│       └── show_schema_comments.py                  以 UTF-8 打印现有注释便于核对
└── src/
    ├── main/
    │   ├── java/com/proxyviewer/
    │   │   ├── Application.java                      Spring Boot 入口（强制 IPv4 栈）
    │   │   ├── config/
    │   │   │   ├── AppProperties.java                app.* 配置绑定
    │   │   │   ├── SecurityConfig.java               Spring Security：表单登录 + 登出 + CSRF Token
    │   │   │   ├── SameOriginFilter.java             同源校验（CSRF 纵深防御）
    │   │   │   └── ScheduledTasks.java               定时测试（可开关/可配周期）
    │   │   ├── controller/
    │   │   │   ├── ProxyController.java              页面路由 + JSON API
    │   │   │   ├── LoginController.java              登录页（认证本身由 Spring Security 完成）
    │   │   │   └── SecurityModelAdvice.java          给模板提供登录状态/用户名
    │   │   ├── model/
    │   │   │   ├── ProxyNode.java                    节点实体（含测试结果与内核参数）
    │   │   │   ├── ProxyNodeRepository.java
    │   │   │   ├── NodeTestRecord.java               测试批次记录
    │   │   │   ├── NodeTestRecordRepository.java
    │   │   │   ├── OperationLog.java                 操作日志
    │   │   │   └── OperationLogRepository.java
    │   │   └── service/
    │   │       ├── SubscriptionService.java          抓取 + 解码（代理 → 直连 → DoH 降级）
    │   │       ├── NodeParser.java                   协议解析 + 国家识别
    │   │       ├── Base64Codec.java                  Base64 三变体解码
    │   │       ├── SubscriptionUrlValidator.java     订阅地址校验（防 SSRF）
    │   │       ├── NodeReconciler.java               增量对账（纯函数，可单测）
    │   │       ├── NodeSyncService.java              对账落库 + 清理失败节点
    │   │       ├── NodeTestService.java              测试编排：TCPing → 真实延迟 → 测速
    │   │       └── test/
    │   │           ├── XrayCoreService.java          内核配置生成 + 进程托管
    │   │           ├── Socks5HttpClient.java         自研 SOCKS5 + HTTP/TLS（JDK 不支持 SOCKS；抓订阅与测节点共用）
    │   │           └── NodeTester.java               单节点真实延迟/测速/UDP 探测
    │   └── resources/
    │       ├── application.properties                默认配置（敏感项走环境变量）
    │       ├── logback-spring.xml                    控制台 + 全量日志 + 独立 OPLOG
    │       └── templates/
    │           ├── login.html                        登录页（表单登录）
    │           ├── index.html                        节点列表主页
    │           ├── logs.html                         操作日志
    │           └── test-logs.html                    测试记录
    └── test/java/com/proxyviewer/
        ├── support/IntegrationTest.java              集成测试组合注解（H2 + MockMvc）
        ├── WebSmokeTest.java                         同源校验 + 模板渲染 + 控制器冒烟
        ├── SecurityFlowTest.java                     表单登录/登出/CSRF Token/401 JSON
        ├── NodeSyncIntegrationTest.java              增量刷新与失败标记（真实数据库）
        ├── RealProxyEndToEndTest.java                自建内核服务端的真实闭环验证
        └── service/ + service/test/                  解析、对账、内核配置、SOCKS5、探测
```

## 快速开始

### 前置条件

- JDK 21+
- Maven 3.9+
- MySQL 8.0

### 1. 创建数据库

```sql
CREATE DATABASE IF NOT EXISTS proxy_viewer
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
```

### 2. 补充表注释 / 字段注释（可选）

表结构由 JPA 实体通过 `ddl-auto=update` 自动创建，**Hibernate 不会写入任何注释**。仓库用 `db/schema-comments.sql` 补齐三张表的中文表注释与全部字段注释：

```powershell
mysql -h 127.0.0.1 -uroot -p --default-character-set=utf8mb4 < db/schema-comments.sql
```

- 必须带 `--default-character-set=utf8mb4`，否则中文注释会乱码；
- 脚本可重复执行，列定义取自库中真实结构，只追加注释、不改字段；
- 注释文案维护在 `db/_tools/gen_schema_comments.py` 中，改完重新生成即可；
- `db/_tools/` 另有两个辅助脚本：`verify_schema_comments.py` 先在克隆表上试跑并逐列比对，`show_schema_comments.py` 按 UTF-8 打印现有注释便于核对。

> 若之后实体字段发生增删改，`ddl-auto=update` 重建该列时其注释会随之丢失，重新执行一次本脚本即可恢复。

### 3. 配置

`src/main/resources/application.properties` **已随仓库提交**，且不含任何口令、也不含任何订阅链接 —— 所有私有项都用环境变量占位。最少需要提供数据库口令与订阅链接：

```powershell
$env:DB_PASSWORD = "你的MySQL密码"
$env:SUBSCRIPTION_URL = "你的订阅链接"
```

> **订阅链接没有默认值。** 仓库刻意不内置任何订阅地址（避免把机场入口写进版本历史）；必须通过 `SUBSCRIPTION_URL` 配置，或每次在页面顶部输入框填写后点击「🔄 更新节点」。

需要固定访问口令时（否则每次启动随机生成并打印到日志）：

```powershell
$env:APP_PASSWORD = "你的面板口令"
```

本机私有配置（订阅链接、数据库/面板口令）建议放在 `src/main/resources/application-dev.properties` —— 它已被 `.gitignore` 忽略，不会进仓库。

它是 **profile 专属配置，必须激活 `dev` profile 才会加载**；而仓库里的 `application.properties` 刻意**不指定任何 profile**（保持仓库中立），激活方式任选：

| 场景 | 做法 |
|------|------|
| **IntelliJ IDEA** | 直接用仓库自带的共享运行配置 **`.run/ProxyViewer-dev.run.xml`**（VM options: `-Dspring.profiles.active=dev`）。若提示找不到模块，在 Run 配置里把模块重新选成 `proxy-subscription-viewer` 即可 |
| 命令行运行 jar | `java -jar target/proxy-subscription-viewer-1.0.0.jar --spring.profiles.active=dev` |
| 环境变量 | `$env:SPRING_PROFILES_ACTIVE = "dev"` |
| Maven 启动 | `mvn spring-boot:run "-Dspring-boot.run.profiles=dev"` |

> 不想用 profile 也可以：直接把第 1 步里的环境变量（`DB_PASSWORD` / `SUBSCRIPTION_URL` / `APP_PASSWORD`）注入即可，无需任何 profile 文件。

### 4. 构建并运行

```powershell
mvn clean package
java -jar target/proxy-subscription-viewer-1.0.0.jar
```

### 5. 访问

打开 <http://localhost:8080>，会跳转到登录页，输入用户名（默认 `admin`）与口令（登录后可随时点右上角「退出登录」）。

启动完成后日志会把访问地址直接打出来（随机端口也会显示真实端口）：

```
========================================================
  proxy-subscription-viewer 启动完成
  系统访问地址: http://127.0.0.1:8080
  访问认证: 已开启（表单登录 /login，用户名 admin）
  订阅链接: 未配置（请在页面填写，或用 SUBSCRIPTION_URL 注入）
========================================================
```

> 首次启动还会在日志中打印随机口令（未设置 `APP_PASSWORD` 时）；首次访问页面是空的 —— 在顶部填写订阅链接后点击"🔄 更新节点"（或预先用 `SUBSCRIPTION_URL` 配好）。

## 配置项

| 配置 | 环境变量 | 默认值 | 说明 |
|------|----------|--------|------|
| `server.address` | `SERVER_ADDRESS` | `127.0.0.1` | 监听地址，默认仅本机 |
| `server.port` | `SERVER_PORT` | `8080` | 端口 |
| `spring.datasource.url` | `DB_URL` | 本机 `proxy_viewer` 库 | JDBC 连接串 |
| `spring.datasource.username` | `DB_USERNAME` | `root` | 数据库用户 |
| `spring.datasource.password` | `DB_PASSWORD` | 空 | 数据库口令 |
| `app.security.enabled` | `APP_AUTH_ENABLED` | `true` | 是否启用认证 |
| `app.security.username` | `APP_USERNAME` | `admin` | 面板用户名 |
| `app.security.password` | `APP_PASSWORD` | 空（随机生成） | 面板口令 |
| `app.subscription.default-url` | `SUBSCRIPTION_URL` | **空（无默认值，必须自行配置）** | 订阅链接；留空时需在页面输入框中填写 |
| `app.subscription.allow-private-hosts` | `ALLOW_PRIVATE_SUBSCRIPTION_HOSTS` | `false` | 是否允许订阅指向内网（SSRF 开关） |
| `app.subscription.proxy-candidates` | `SUBSCRIPTION_PROXIES` | `socks5://127.0.0.1:10808,http://127.0.0.1:10809` | 依次尝试的本地代理 |
| `app.subscription.raw-socket-fallback-enabled` | `RAW_SOCKET_FALLBACK` | `true` | 代理/直连都失败时用 DoH + 裸 TLS |
| `app.subscription.doh-server-ip` / `doh-server-host` | `DOH_SERVER_IP` / `DOH_SERVER_HOST` | `8.8.8.8` / `dns.google` | DoH 解析器 |
| `app.subscription.keep-data-on-empty-result` | — | `true` | 抓取为空时保留旧数据 |
| `app.test.core-path` | `XRAY_PATH` | 空（自动探测） | Xray 内核可执行文件路径 |
| `app.test.core-dir` | `XRAY_DIR` | 空（自动探测） | 内核所在目录；v2rayN 便携版通常是 `…\v2rayN-windows-64\bin` |
| `app.test.core-asset-dir` | `XRAY_ASSET_DIR` | 空（用内核目录） | 含 `geoip.dat`/`geosite.dat` 的目录，作为 `XRAY_LOCATION_ASSET` 传给内核 |
| `app.test.core-startup-timeout-ms` | — | `8000` | 内核启动到就绪的最长等待（就绪判定是真实 SOCKS5 握手） |
| `app.test.tcping-pre-filter` | `TCPING_PREFILTER` | `true` | 先用 TCPing 剔除端口不通的节点，不为死节点启动内核 |
| `app.test.tcping-threads` / `tcping-timeout-ms` | — | `50` / `5000` | TCPing 并发度与超时 |
| `app.test.latency-concurrency` | `TEST_LATENCY_CONCURRENCY` | `8` | 真实延迟测试的**并发内核数** |
| `app.test.latency-test-url` | `LATENCY_TEST_URL` | `http://cp.cloudflare.com/generate_204` | 真实延迟探测地址，要求返回 204/200 |
| `app.test.latency-timeout-ms` / `latency-attempts` | — | `5000` / `2` | 单次探测超时；连测次数（取最小值） |
| `app.test.speed-test-enabled` | `SPEED_TEST_ENABLED` | `true` | 是否对延迟通过的节点做下载测速 |
| `app.test.speed-test-url` | `SPEED_TEST_URL` | `https://speed.cloudflare.com/__down?bytes=50000000` | 测速下载地址 |
| `app.test.speed-test-duration-ms` | — | `10000` | 限时下载时长，到点即中断 |
| `app.test.speed-concurrency` | — | `3` | 测速并发内核数（测速会争抢带宽，不宜高） |
| `app.test.udp-test-enabled` | `UDP_TEST_ENABLED` | `false` | 是否做 UDP 可用性测试（会明显拉长整批耗时） |
| `app.test.udp-test-target` | — | `ntp:pool.ntp.org` | UDP 探测目标，格式 `类型:主机[:端口]`，类型支持 `ntp`/`dns` |
| `app.test.auto-delete-after-failures` | `AUTO_DELETE_AFTER_FAILURES` | `0` | 连续失败 N 次后自动删除，`0`=永不 |
| `app.test.schedule-enabled` | `TEST_SCHEDULE_ENABLED` | `true` | 定时测试开关 |
| `app.test.schedule-fixed-rate-ms` | — | `21600000` | 测试周期（6 小时） |
| `spring.thymeleaf.cache` | `THYMELEAF_CACHE` | `true` | 开发改模板时设为 `false` |

## 页面与接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/` | 主页：节点分页、国家筛选、延迟排序、统计；含「🚀 测试真实可用性」按钮与实时进度 |
| POST | `/refresh` | 抓取订阅并增量入库（PRG 重定向回首页） |
| POST | `/test` | 触发**真实可用性测试**：立即返回，测试转后台执行（无 JS 时的兜底入口） |
| POST | `/api/test/start` | 页内按钮入口：开始测试并立即返回当前进度（已有测试在跑时返回 `409`） |
| GET | `/api/test/status` | 测试进度：`running`/`phase`/`done`/`total`/`elapsedMs`/`message`/`events` |
| POST | `/api/copy` | 请求体 `{"ids":[1,2,3]}`，返回原始订阅链接纯文本 |
| POST | `/api/delete` | 请求体 `{"ids":[...]}`，删除指定节点 |
| POST | `/api/purge-failed` | 清理所有被标记为失败的节点 |
| GET | `/logs` | 操作日志（刷新/复制/删除/清理） |
| GET | `/test-logs` | 测试批次历史（总计/真实可达/失败/平均延迟/已测速/UDP 可用/耗时） |

所有状态变更接口都是 **POST**，必须携带 **CSRF Token** 并通过同源校验（见下）；未登录访问页面跳转 `/login`，`/api/**` 返回 `401` JSON。

## 安全设计

| 议题 | 处理方式 |
|------|----------|
| 未授权访问 | Spring Security 表单登录（`/login`，会话保存在 `JSESSIONID`），支持页面右上角「退出登录」；未配置口令时启动随机生成并打印，杜绝弱默认口令 |
| 暴露面 | 默认只监听 `127.0.0.1`；未使用 actuator（已从依赖中移除） |
| SSRF | 订阅地址只允许 `http/https`、禁止 userinfo；拒绝回环/私有/链路本地/组播/保留地址；**跟随重定向后再次校验最终地址**；DoH 解析结果同样校验。本地 DNS 解析失败时放行（因为真实解析发生在代理/DoH 侧） |
| CSRF | ① Spring Security CSRF Token（会话同步令牌）：表单由 Thymeleaf 自动注入 `_csrf` 隐藏域，页面里的 `fetch` 从 `_csrf` meta 标签取值放 `X-CSRF-TOKEN` 请求头；② `SameOriginFilter` 另对非安全方法校验 `Sec-Fetch-Site`/`Origin`/`Referer` 与 Host 是否一致，作为纵深防御 |
| 会话加固 | `JSESSIONID` 为 `HttpOnly` + `SameSite=Lax`，且只用 Cookie 跟踪（不拼进 URL） |
| 状态变更语义 | `/refresh`、`/test`、删除、清理全部为 POST，不再能被预取或爬虫误触发 |
| 凭据落盘 | 配置文件中不含任何口令；口令用 bcrypt 编码后比对；日志仅在未配置时打印一次随机口令 |

> ⚠️ 本应用能读取订阅中的全部节点（含 UUID，等同代理凭据）。若确需对外暴露，请自行在前面加 HTTPS 反向代理，并务必设置强口令。

## 节点测试策略（真实可用性测试）

> **重构说明**：旧实现对节点服务器端口直接发 HTTP/WS 握手请求，只能证明"端口开着"，
> 把大量已被阻断的节点判为可达。现在改为与 **v2rayN 的 Realping / Speedtest 同源**的做法：
> **为每个节点生成真实内核配置、启动 Xray 内核，再经内核的本地 SOCKS 入站真实访问外网**。
> 实测证据：同一份订阅里 120 个挤在同一地址的节点，TCP 可连但 TLS 立刻被重置——
> 旧实现会把它们全部标为"可达"，新实现全部正确判为失败。
>
> 参考 v2rayN 源码的实现细节（含各测试类型的默认参数、内核生命周期做法、以及本项目刻意
> 不照抄的几个点）见 [`docs/v2rayn-testing-reference.md`](docs/v2rayn-testing-reference.md)
> 与 [`docs/v2rayn-speedtest-udptest-findings.md`](docs/v2rayn-speedtest-udptest-findings.md)。

### 三个阶段

| 阶段 | 做什么 | 为什么 |
|------|--------|--------|
| ① TCPing 预筛 | 纯 TCP 连接测时，遍历该域名解析出的**全部** IP 取最快者（v2rayN 只取第一个） | 不启动内核，成本极低；先剔除端口不通的节点，省掉为死节点起内核的开销 |
| ② 真实延迟 | 为节点启动独立内核 → 经其 SOCKS 入站访问 `http://cp.cloudflare.com/generate_204`，**要求返回 204/200**；连测 2 次取最小值 | 这是"这个代理真的能不能用"的唯一可靠口径。节点被墙时常见 403/502，v2rayN 不校验状态码，本项目会判为失败 |
| ③ 下载测速 | 仅对延迟通过的节点执行，限时 10 秒下载，速度 = 字节数 × 8 ÷ 耗时毫秒数（Kbps） | 延迟不通就不必浪费带宽 |

UDP 可用性测试（`app.test.udp-test-enabled=true` 时启用）经 SOCKS5 UDP ASSOCIATE 发 NTP 探测包并**校验响应**，
用于识别"TCP 通但 UDP 被阻断"的节点。

### 关键实现要点（都是踩过的坑）

- **内核就绪判定用真实 SOCKS5 握手**（`05 01 00` → 期望首字节 `0x05`），不是只探 TCP 端口：
  端口可能恰被其它服务占用，只探 TCP 会得到"假就绪"。v2rayN 在测速路径上是盲等 1 秒。
- **通信层自己实现 SOCKS5，不用 `java.net.http.HttpClient`**：JDK 的 HttpClient **不支持 SOCKS 代理**，
  配置 `Proxy.Type.SOCKS`（无论 ProxySelector 还是 `socksProxyHost` 系统属性）都会被**静默忽略**，
  请求退化成直连——那样任何节点都会"测通"。
- **订阅抓取同样受这条限制**：`app.subscription.proxy-candidates` 里 `socks5://` 的候选交给自研
  `Socks5HttpClient`（TLS + 手动跟随重定向 + 每跳 SSRF 复核，主机名 `remoteDns=true` 交代理侧解析），
  只有 `http://` 代理候选与"直连"才用 `HttpClient`。历史上 SOCKS 候选是用 `HttpClient` 发的，
  结果"🔄 更新节点"必然 connect timeout——请求其实在直连被墙的订阅域名。
  抓取失败时页面与操作日志会列出**每个通道各自的失败原因**，便于判断是代理没开还是订阅地址错了。
- **每节点独立内核**：节点之间互不干扰，单节点失败不影响整批；内核用完即关（`finally`），
  临时配置写在系统临时目录并在关闭时删除。v2rayN 的 `configTest*.json` 是**从不清理**的。
- **补齐了生成内核配置所需的参数**：`flow` / `pbk` / `sid` / `spx` / `serviceName` / `headerType` / `skipCertVerify`。
  缺 `flow` 会让 Vision 节点必然握手失败而被误判为节点失效；缺 `pbk` 的 Reality 节点会直接报
  `REALITY_MISSING_PUBLIC_KEY` 而不是让内核神秘失败。
- **内核位置不缓存"未找到"**：配置或安装可能在运行期才补上，缓存失败结果会造成"配置已对却仍报找不到内核"。

### 进度反馈

整批测试要跑几十秒到几分钟，因此**点击后立即返回、测试在后台执行**，前端每秒轮询
`/api/test/status` 展示：当前阶段、已完成/总数（带进度条）、已用时，以及最近处理过的
节点流水（`✓ 节点 220ms` / `✗ 节点 RESET`）。

早期实现让 HTTP 请求同步等待整批跑完，页面在整段时间内毫无反馈，看起来像卡死了。
几处刻意的设计：

- **进度总量会随阶段收敛**：TCPing 预筛阶段是全部节点，进入真实延迟测试后总量改为
  「TCP 可达的节点数」，否则进度条永远到不了 100%
- **阶段切换写进同一份快照**，避免前端多次读取时看到互相矛盾的状态
- **测试进行中可以离开或刷新页面**，重新进入会自动接管显示进度
- 内核启动失败时只提取日志里的错误行（不把版本横幅整段塞进页面）

### 失败处理

- **失败不删除节点**：只写入 `FAILED:原因` 标记并累加"连续失败次数"，单次网络抖动或断网不会清空数据库
- 自动删除仅在显式配置 `app.test.auto-delete-after-failures=N`（N>0）且连续失败达到 N 次时发生
- 需要清理时在主页点击"🧹 清理失败节点"
- 定时任务与手动测试互斥，不会并发重叠

## 前置条件：准备代理内核

节点测试需要本机的 **Xray 内核**。若你装过 v2rayN，它自带内核，直接指过去即可：

```properties
# src/main/resources/application-dev.properties
app.test.core-dir=S:\installationFree\v2rayN-windows-64\bin
```

或用环境变量/启动参数指定可执行文件：

```powershell
$env:XRAY_PATH = "S:\installationFree\v2rayN-windows-64\bin\xray\xray.exe"
```

留空时会自动探测若干常见位置（PATH、`<盘>:\installationFree\v2rayN*\bin`、用户下载目录等）。

> 未找到内核时，页面会显示明确的配置提示，且**不会**把节点误判为失败——测试按钮会被禁用。
> 内核必须比 v2rayN 自带的更"新"程度无关紧要，本项目只用其 `run -c <config>` 能力。

## 解析与字段说明

- **VLESS** — 解析 `vless://uuid@server:port?params#name`，支持 IPv6 字面量 `[::1]:443`；`security=tls` 会归一化到 TLS 字段（否则页面 TLS 列永远显示 `—`）
- **VMESS** — 解析 `vmess://base64(json)` 中的 `add/port/id/aid/net/path/host/tls/sni/fp/alpn`
- **国家识别** —
  1. 扫描节点名中**成对的区域指示符**（国旗 emoji）→ 得到真实两字母代码 → 查表转中文
  2. 未命中时用正则 `(?<![A-Za-z])([A-Z]{2})(?=[-_ .|/]|$)` 回退，且必须命中国家表

  因此 🏁 之类的非国旗 emoji 不会再被误判成国家，`AY`、`ZZ`、`HK01` 这类也不会误命中。

## 数据库表结构

三张表全部由 JPA 实体生成（`ddl-auto=update`），注释由 `db/schema-comments.sql` 补齐。

| 表 | 对应实体 | 说明 |
|------|----------|------|
| `proxy_nodes` | `ProxyNode` | 节点明细，含测试结果字段；行由 `NodeReconciler` 按节点指纹做增量对账 |
| `node_test_records` | `NodeTestRecord` | 每批次测试一行汇总，供 `/test-logs` 分页展示 |
| `operation_logs` | `OperationLog` | 刷新/复制/删除/清理的操作审计，供 `/logs` 分页展示 |

几个容易误读的字段：

- `proxy_nodes.uuid_` — 列名带下划线是为了避开 `uuid` 关键字，实际是 VLESS 的 uuid / VMESS 的 id
- `proxy_nodes.last_test_result` — `OK` 为**真实可用**，`FAILED:原因` 为失败，`NULL` 表示尚未测过；「清理失败节点」按 `FAILED:` 前缀筛选
- `proxy_nodes.consecutive_failures` — 失败只累加本字段并打标记；仅当 `app.test.auto-delete-after-failures=N`（N>0）时才自动删除
- `proxy_nodes.latency_ms` — **真实延迟**（经代理访问探测地址的往返耗时），不是 TCP 建连耗时；`-1` 表示未测或不可达
- `proxy_nodes.speed_kbps` — 限时下载测速结果（Kbps）；`-1` 表示未测速（延迟未通过的节点不测速）
- `proxy_nodes.flow` / `public_key` / `short_id` / `spider_x` / `service_name` / `header_type` — 生成内核配置所需的 VLESS/REALITY/gRPC 参数；缺失会导致对应节点被误判为失效
- `node_test_records.avg_latency_ms` — 本批次可达节点的平均真实延迟；无可达节点时为 `-1`
- `operation_logs.node_count` — 刷新记最终节点总数，复制/删除/清理记实际处理行数

## 测试

```powershell
mvn test
```

覆盖 122 个用例：

- `NodeParserTest` — VLESS（含 IPv6、缺省端口、`flow`/`pbk`/`sid`/`serviceName` 等新参数）、VMESS、国旗/两字母码国家识别与历史误判回归
- `NodeReconcilerTest` — 新增/更新/删除划分、主键与测试结果保留、订阅内与库内重复去重、新增字段的可变拷贝
- `SubscriptionPayloadTest` — 明文与三种 Base64 变体、chunked 解码容错
- `SubscriptionUrlValidatorTest` — 公网/内网/保留地址、IPv4-mapped IPv6、非 http 协议、userinfo
- `XrayCoreServiceTest` — 内核配置生成：VLESS/VMESS、TLS/REALITY、ws/grpc/xhttp、TCP 伪装头、参数不足时明确报错
- `CoreErrorExtractionTest` — 内核启动失败时从日志提取错误行（而不是把版本横幅整段塞进页面）
- `Socks5HttpClientTest` — 自研 SOCKS5 握手报文、HTTP 状态行/响应体解析、chunked 解码、远程 DNS 报文，以及**代理指向死端口时必须失败**（防止退回被静默忽略代理的 HttpClient）
- `SubscriptionServiceSocks5Test` — 用本地假 SOCKS5 代理验证**订阅刷新确实穿过代理**：目标域名由代理侧解析、失败时逐通道给出原因（回归"点击刷新节点失败"）
- `NodeTesterTest` — UDP 帧编解码、NTP 报文构造与响应校验、探测目标解析、失败原因归类
- `CoreServiceIntegrationTest` — 真实起停 Xray 内核：定位、握手就绪、关闭后端口释放与临时配置清理
- `CredentialMismatchDiagnosticTest` — 用只接受指定 UUID 的自建服务端验证：**凭据错误必须判为不可用**（若为可达即说明请求没走代理）
- `RealProxyEndToEndTest` — 本机自建 Xray 服务端作为"节点"的闭环：真实测出延迟、不可达节点被正确标记、失败节点不被物理删除
- `NodeTestProgressTest` — 测试触发立即返回（不阻塞页面）、进度可观察、结束后状态复位、重复触发被拒
- `WebSmokeTest` — 跨站 403、同源放行、三个模板渲染、`/refresh` 不接受 GET
- `SecurityFlowTest` — 表单登录/登出、口令错误提示、CSRF Token 缺失 403、`/api` 未登录 401 JSON、Basic 头不再生效
- `NodeSyncIntegrationTest` — 真实数据库下的增量刷新（id 与测速结果保持）与"失败只标记 + 手动清理"

集成测试使用 H2 内存库，不需要 MySQL。

测试环境备注（JDK 25 + Spring Boot 4.1.1）：

- Mockito 通过 `src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker` 固定为 subclass mock maker，
  不依赖 JDK 21+ 已禁止的 agent 动态挂载（JEP 451）；代价是不能 mock final 类 / 静态方法 / 构造器；
- Mockito 5.23.0 与 Byte Buddy 1.18.11 由 Spring Boot 4.1.1 统一管理，已原生支持 JDK 25，
  因此 pom 里不再手工提升这两个依赖的版本；
- MockMvc 相关测试需要显式引入 `spring-boot-webmvc-test` 模块，否则 `@AutoConfigureMockMvc` 无法解析。

## 常见问题

**Q: 页面打开是空的？**
A: 首次启动不会自动抓取（旧版 README 曾这样描述，属于文档错误）。点击"🔄 更新节点"或空状态里的"点击此处刷新"。

**Q: 忘了面板口令？**
A: 设置环境变量 `APP_PASSWORD` 后重启；或直接看启动日志里打印的随机口令。

**Q: 订阅刷新失败（connect timed out）？**
A: 说明所有通道都没走通，页面上的失败信息会逐个列出原因。抓取顺序是：`app.subscription.proxy-candidates`
里的代理候选（`socks5://127.0.0.1:10808` 走自研 SOCKS5 实现，主机名交代理侧解析；`http://127.0.0.1:10809`
走 JDK HttpClient）→ 直连 → DoH 手动解析 + 裸 TLS 直连。判断方法：
- 看到"连接被拒绝"→ 本地代理端口没监听（v2rayN 没开，或端口不是 10808/10809）
- 看到"连接超时"→ 代理在跑但代理本身出不了网（节点/路由没连上）
- 看到"DNS 解析失败"→ 走的是直连通道，而本地 DNS 被污染
- 只有 DoH 那一路失败是正常的：国内通常到不了 `8.8.8.8`，它只是最后的兜底

**Q: 为什么有些节点没有速度？**
A: 明文 TCP 节点无法做 HTTP 测速，速度列为 `N/A` 属正常；只有 TLS/WS 节点会做限时下载测速。测速可通过 `app.test.speed-test-enabled=false` 关闭。

**Q: 刷新后节点的延迟数据没了？**
A: 正常情况下不会。增量刷新会保留未变化节点的测速结果；只有订阅中已消失的节点才会被删除。

**Q: 为什么删除 / 清理接口提示 403？**
A: 两种可能：一是缺少/携带了失效的 CSRF Token（脚本调用需先登录拿会话，再从页面 HTML 的
`_csrf` meta 标签取值放进 `X-CSRF-TOKEN` 请求头）；二是触发了同源校验（`Sec-Fetch-Site: cross-site`
或 Origin/Referer 与 Host 不一致）。这些接口必须从本页面发起或由同源脚本调用。

**Q: 脚本怎么调用这些接口？**
A: 表单登录后用会话 Cookie（`JSESSIONID`）保持登录态；状态变更请求（POST）带上
`X-CSRF-TOKEN: <页面 _csrf meta 的值>` 即可。若只是本机调试、不想处理会话与 Token，
可临时设 `APP_AUTH_ENABLED=false`（认证与 CSRF 校验都会关闭，不建议对外使用）。

**Q: 页面提示"未找到代理内核"？**
A: 本机没有可用的 Xray 内核。若装过 v2rayN，把 `app.test.core-dir` 指向它的 `bin` 目录即可；
否则用 `XRAY_PATH` 指向 `xray.exe`。未找到内核时测试按钮会被禁用——这是刻意的，
避免把"测不了"误报成"节点全挂了"。

**Q: 测试很慢（几百个节点要几分钟）？**
A: 每个节点都要真实启动一次内核并访问外网，这是"真实测试"的固有成本（v2rayN 同理）。
可调整：`app.test.tcping-pre-filter=true` 先剔除端口不通的节点；降低 `speed-concurrency` 以外的
`latency-concurrency` 不会更快（过高的并发反而互相干扰）；不需要测速时设 `speed-test-enabled=false`。

**Q: 为什么几乎所有节点都失败了？**
A: 先用 TCPing 预筛看"TCP 可达"的数量：若预筛就大量失败，说明订阅里的地址本身已失效或被封。
若 TCP 可达但真实延迟失败（原因多为 `RESET`），说明端口还开着但代理服务已被阻断——
这正是旧实现会误判为"可达"的情形。本项目的判定是真实的。

## 已知限制

- 单用户、无 HTTPS、无角色权限体系，仅适合个人在内网或加反代后使用
- 只支持 VLESS 与 VMESS。订阅里的 trojan / hysteria / ss 等协议在解析阶段就被忽略
- 真实延迟用 HTTP 探测点衡量，反映的是"经该节点访问探测点"的往返耗时；
  与具体客户端在特定网络下的体验仍可能有差异
- 测速是**单线程下载**的吞吐量，不测上传，也非多线程满速能力
- 内核每个节点起停一次，节点数很多时整批耗时较长
