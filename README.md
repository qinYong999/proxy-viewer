# Proxy Subscription Viewer

代理订阅节点管理面板 —— 从订阅链接抓取节点配置，解析后存入 MySQL，通过 Web 页面浏览、筛选、测速、复制和清理。

定位是**个人自用的订阅聚合与可用性看门狗**：它不转发流量，只负责把订阅里的节点整理成可读、可测、可复制的一览表。

## 功能概览

- 🔄 **订阅抓取** — base64 或明文订阅均可，自动识别；三级传输降级（本地代理 → 直连 → DoH + 裸 TLS 直连）应对被污染的本地 DNS
- 📦 **增量入库** — 按节点指纹做差集（新增/更新/删除），未变化节点保留主键与测速结果，订阅内重复条目自动去重
- 📋 **节点展示** — 表格展示地址、端口、协议、传输、TLS、路径、SNI、Host，支持 20/50/100/200 分页与跳页
- 🔍 **国家筛选** — 按国家/地区筛选，统计数据（总数/VLESS/VMESS/WS/TCP/可达/失败）同步更新
- ↕️ **延迟排序** — 点击"延迟"列在 升序 → 降序 → 默认 之间循环
- ☑️ **多选复制** — 勾选节点一键复制原始订阅链接（`vless://` / `vmess://`），兼容 v2rayN、Shadowrocket、Clash Meta
- 🔬 **连通性测试** — 定时（默认 6 小时）+ 手动触发，TLS/WS 节点做真实下载测速并记录延迟
- 🧹 **失败节点管理** — 测试失败只打标记不删数据，页面可一键清理失败节点
- 📊 **审计与历史** — 操作日志（刷新/复制/删除/清理）与测试批次记录
- 🔐 **访问控制** — 默认开启 HTTP Basic 认证，随机口令兜底，默认只监听 127.0.0.1

## 技术栈

| 层级 | 技术 |
|------|------|
| 语言 | Java 21+ |
| 框架 | Spring Boot 3.4.1 |
| ORM | Spring Data JPA + Hibernate |
| 数据库 | MySQL 8.0（测试用 H2 内存库） |
| 模板引擎 | Thymeleaf |
| 前端 | 原生 HTML/CSS/JS（无构建步骤） |
| 构建 | Maven 3.9+ |
| HTTP 客户端 | `java.net.http.HttpClient` + 裸 `SSLSocket`（DoH 降级通道） |
| 测试 | JUnit 5 + AssertJ + MockMvc |

## 项目结构

```
proxy-viewer/
├── pom.xml
├── README.md
└── src/
    ├── main/
    │   ├── java/com/proxyviewer/
    │   │   ├── Application.java                      Spring Boot 入口（强制 IPv4 栈）
    │   │   ├── config/
    │   │   │   ├── AppProperties.java                app.* 配置绑定
    │   │   │   ├── BasicAuthFilter.java              HTTP Basic 认证
    │   │   │   ├── SameOriginFilter.java             CSRF 同源校验
    │   │   │   └── ScheduledTasks.java               定时测试（可开关/可配周期）
    │   │   ├── controller/
    │   │   │   └── ProxyController.java              页面路由 + JSON API
    │   │   ├── model/
    │   │   │   ├── ProxyNode.java                    节点实体（含测试结果字段）
    │   │   │   ├── ProxyNodeRepository.java
    │   │   │   ├── NodeTestRecord.java               测试批次记录
    │   │   │   ├── NodeTestRecordRepository.java
    │   │   │   ├── OperationLog.java                 操作日志
    │   │   │   └── OperationLogRepository.java
    │   │   └── service/
    │   │       ├── SubscriptionService.java          抓取 + 解码（三级降级）
    │   │       ├── NodeParser.java                   协议解析 + 国家识别
    │   │       ├── Base64Codec.java                  Base64 三变体解码
    │   │       ├── SubscriptionUrlValidator.java     订阅地址校验（防 SSRF）
    │   │       ├── NodeReconciler.java               增量对账（纯函数，可单测）
    │   │       ├── NodeSyncService.java              对账落库 + 清理失败节点
    │   │       └── NodeTestService.java              并发连通性测试 + 测速
    │   └── resources/
    │       ├── application.properties                默认配置（敏感项走环境变量）
    │       ├── logback-spring.xml                    控制台 + 全量日志 + 独立 OPLOG
    │       └── templates/
    │           ├── index.html                        节点列表主页
    │           ├── logs.html                         操作日志
    │           └── test-logs.html                    测试记录
    └── test/java/com/proxyviewer/
        ├── support/IntegrationTest.java              集成测试组合注解（H2 + MockMvc）
        ├── WebSmokeTest.java                         认证/同源/模板渲染
        ├── NodeSyncIntegrationTest.java              增量刷新与失败标记（真实数据库）
        └── service/
            ├── NodeParserTest.java                   VLESS/VMESS/国家识别
            ├── NodeReconcilerTest.java               增量对账
            ├── SubscriptionPayloadTest.java          Base64/chunked/明文订阅
            └── SubscriptionUrlValidatorTest.java     SSRF 校验
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

### 2. 配置

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

### 3. 构建并运行

```powershell
mvn clean package
java -jar target/proxy-subscription-viewer-1.0.0.jar
```

### 4. 访问

打开 <http://localhost:8080>，输入用户名（默认 `admin`）与口令。

启动完成后日志会把访问地址直接打出来（随机端口也会显示真实端口）：

```
========================================================
  proxy-subscription-viewer 启动完成
  系统访问地址: http://127.0.0.1:8080
  访问认证: 已开启（用户名 admin）
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
| `app.test.thread-pool-size` | `TEST_THREADS` | `20` | 测试并发度 |
| `app.test.speed-test-enabled` | — | `true` | 是否对 TLS 节点测速 |
| `app.test.auto-delete-after-failures` | `AUTO_DELETE_AFTER_FAILURES` | `0` | 连续失败 N 次后自动删除，`0`=永不 |
| `app.test.schedule-enabled` | `TEST_SCHEDULE_ENABLED` | `true` | 定时测试开关 |
| `app.test.schedule-fixed-rate-ms` | — | `21600000` | 测试周期（6 小时） |
| `spring.thymeleaf.cache` | `THYMELEAF_CACHE` | `true` | 开发改模板时设为 `false` |

## 页面与接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/` | 主页：节点分页、国家筛选、延迟排序、统计 |
| POST | `/refresh` | 抓取订阅并增量入库（PRG 重定向回首页） |
| POST | `/test` | 手动触发连通性测试，完成后跳转 `/test-logs` |
| POST | `/api/copy` | 请求体 `{"ids":[1,2,3]}`，返回原始订阅链接纯文本 |
| POST | `/api/delete` | 请求体 `{"ids":[...]}`，删除指定节点 |
| POST | `/api/purge-failed` | 清理所有被标记为失败的节点 |
| GET | `/logs` | 操作日志（刷新/复制/删除/清理） |
| GET | `/test-logs` | 测试批次历史（总计/可达/失败/自动删除/耗时） |

所有状态变更接口都是 **POST**，并且必须通过同源校验（见下）；无认证访问一律 `401`。

## 安全设计

| 议题 | 处理方式 |
|------|----------|
| 未授权访问 | 默认开启 HTTP Basic 认证；未配置口令时启动随机生成并打印，杜绝弱默认口令 |
| 暴露面 | 默认只监听 `127.0.0.1`；未使用 actuator（已从依赖中移除） |
| SSRF | 订阅地址只允许 `http/https`、禁止 userinfo；拒绝回环/私有/链路本地/组播/保留地址；**跟随重定向后再次校验最终地址**；DoH 解析结果同样校验。本地 DNS 解析失败时放行（因为真实解析发生在代理/DoH 侧） |
| CSRF | `SameOriginFilter` 对非安全方法校验 `Sec-Fetch-Site`/`Origin`/`Referer` 与 Host 是否一致；Basic 凭据会被浏览器按目标源自动附带，因此这一步是必需的 |
| 状态变更语义 | `/refresh`、`/test`、删除、清理全部为 POST，不再能被预取或爬虫误触发 |
| 凭据落盘 | 配置文件中不含任何口令；日志仅在未配置时打印一次随机口令 |

> ⚠️ 本应用能读取订阅中的全部节点（含 UUID，等同代理凭据）。若确需对外暴露，请自行在前面加 HTTPS 反向代理，并务必设置强口令。

## 节点测试策略

- 明文 TCP 节点：DNS + TCP 连接 + **协议字节探针**（发一个 vless/vmess 版本字节，观察服务端是否立刻关闭），据此识别"端口开着但不是代理"的服务
- TLS / WS 节点：按 SNI 握手后发送 HTTP 或 WebSocket 升级请求，依据 `HTTP/` 前缀或 `101 Switching Protocols` 判断；成功后再做限时下载测速
- **失败不再删除节点**：只写入 `FAILED:原因` 标记并累加"连续失败次数"。一次断网、一次抖动都不会再清空数据库
- 自动删除仅在显式配置 `app.test.auto-delete-after-failures=N`（N>0）且连续失败达到 N 次时发生
- 需要清理失败节点时，在主页点击"🧹 清理失败节点"（或在 `/test-logs` 查看历史后清理）
- 定时任务与手动测试互斥，不会并发重叠
- 明文 TCP 节点无法测速，速度列显示 `N/A`，**不会再用 RTT 公式编造速度值**

## 解析与字段说明

- **VLESS** — 解析 `vless://uuid@server:port?params#name`，支持 IPv6 字面量 `[::1]:443`；`security=tls` 会归一化到 TLS 字段（否则页面 TLS 列永远显示 `—`）
- **VMESS** — 解析 `vmess://base64(json)` 中的 `add/port/id/aid/net/path/host/tls/sni/fp/alpn`
- **国家识别** —
  1. 扫描节点名中**成对的区域指示符**（国旗 emoji）→ 得到真实两字母代码 → 查表转中文
  2. 未命中时用正则 `(?<![A-Za-z])([A-Z]{2})(?=[-_ .|/]|$)` 回退，且必须命中国家表

  因此 🏁 之类的非国旗 emoji 不会再被误判成国家，`AY`、`ZZ`、`HK01` 这类也不会误命中。

## 测试

```powershell
mvn test
```

覆盖 40 个用例：

- `NodeParserTest` — VLESS（含 IPv6、缺省端口、参数解码）、VMESS、国旗/两字母码国家识别与历史误判回归
- `NodeReconcilerTest` — 新增/更新/删除划分、主键与测试结果保留、订阅内与库内重复去重
- `SubscriptionPayloadTest` — 明文与三种 Base64 变体、chunked 解码容错
- `SubscriptionUrlValidatorTest` — 公网/内网/保留地址、IPv4-mapped IPv6、非 http 协议、userinfo
- `WebSmokeTest` — 401 认证、跨站 403、同源放行、三个模板渲染、`/refresh` 不接受 GET
- `NodeSyncIntegrationTest` — 真实数据库下的增量刷新（id 与测速结果保持）与"失败只标记 + 手动清理"

集成测试使用 H2 内存库，不需要 MySQL。

## 常见问题

**Q: 页面打开是空的？**
A: 首次启动不会自动抓取（旧版 README 曾这样描述，属于文档错误）。点击"🔄 更新节点"或空状态里的"点击此处刷新"。

**Q: 忘了面板口令？**
A: 设置环境变量 `APP_PASSWORD` 后重启；或直接看启动日志里打印的随机口令。

**Q: 订阅刷新失败（connect timed out）？**
A: 本地 DNS 可能被污染。应用会依次尝试：本地代理（`127.0.0.1:10808` SOCKS5 / `10809` HTTP）→ 直连 → DoH 手动解析 + 裸 TLS 直连。若使用 v2rayN，请确认端口为默认的 10808/10809，或用 `app.subscription.proxy-candidates` 自定义。

**Q: 为什么有些节点没有速度？**
A: 明文 TCP 节点无法做 HTTP 测速，速度列为 `N/A` 属正常；只有 TLS/WS 节点会做限时下载测速。测速可通过 `app.test.speed-test-enabled=false` 关闭。

**Q: 刷新后节点的延迟数据没了？**
A: 正常情况下不会。增量刷新会保留未变化节点的测速结果；只有订阅中已消失的节点才会被删除。

**Q: 为什么删除 / 清理接口提示 403？**
A: 触发了同源校验。这些接口必须从本页面发起（或由同源的脚本调用），跨站请求会被拒绝。

## 已知限制

- 单用户、无 HTTPS、无角色权限体系，仅适合个人在内网或加反代后使用
- 节点测试是"端口/协议可用性 + 单次 HTTP 响应"级别的探测，不能等同于真实客户端的可用性
- Reality / XTLS 等特殊协议按普通 TLS/TCP 对待，测试结果仅供参考
