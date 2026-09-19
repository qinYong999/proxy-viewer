# v2rayN「节点测试」实现原理技术报告

调研对象：https://github.com/2dust/v2rayN （master 分支，C#/.NET）
调研方法：逐一 web_fetch raw.githubusercontent.com / api.github.com 实读源码；仅记录实际读到的内容。
所有行号仅 `ConfigHandler.cs` 可得（来自抓取落盘副本），其余以 raw URL 引用。

---

## (a) 架构总览

### a.1 入口链路

```
ProfilesViewModel.ServerSpeedtest(ESpeedActionType)      // ServiceLib/ViewModels/ProfilesViewModel.cs
   └─ SpeedtestService.RunLoop(actionType, selecteds)     // ServiceLib/Services/SpeedtestService.cs
        └─ RunAsync() switch(actionType)
             ├─ Tcping      -> RunTcpingAsync()          // 完全不启动内核
             ├─ Realping    -> RunRealPingBatchAsync()   // 复用 1 个内核（批量）
             ├─ UdpTest     -> RunUdpTestBatchAsync()    // 复用 1 个内核（批量）
             ├─ Speedtest   -> RunMixedTestAsync(..., concurrency=1,  blSpeedTest=true)
             └─ Mixedtest   -> RunMixedTestAsync(..., concurrency=MixedConcurrencyCount, blSpeedTest=true)
```

来源：<https://raw.githubusercontent.com/2dust/v2rayN/master/v2rayN/ServiceLib/Services/SpeedtestService.cs>

### a.2 两种截然不同的内核使用形态（核心结论）

| 测试类型 | 是否起内核 | 内核进程数 | 节点→出站映射方式 |
|---|---|---|---|
| Tcping | **否** | 0 | 直接 TCP connect 节点的 address:port |
| Realping | 是 | **每批 1 个**（批大小 ≤1000，失败后减半重试） | 每节点独占一个本地入站**端口**，路由规则 `inboundTag→outboundTag` |
| UdpTest | 是 | **每批 1 个** | 同上 |
| Speedtest | 是 | **每节点 1 个**（顺序，并发=1） | 单节点配置里只有一个 socks 入站 |
| Mixedtest | 是 | **每节点 1 个**（并发=MixedConcurrencyCount，默认 5） | 同上 |

**所以答案是「两种都用」**：延迟类测试（Realping/UdpTest）用「一个内核 + N 个入站端口 + 路由区分」；测速类测试（Speedtest/Mixedtest）为每个节点单独起内核。

### a.3 「复用同一个内核」时如何把流量路由到不同出站

**靠端口，不靠 socks 用户名/密码。** v2rayN 的测速入站全部是 `auth: "noauth"`。

批量路径（`CoreConfigV2rayService.GenerateClientSpeedtestConfig(List<ServerTestItem>)`）：

```csharp
var initPort = AppManager.Instance.GetLocalPort(EInboundProtocol.speedtest);
foreach (var it in selecteds) {
    // 从 initPort 向上扫描第一个未被占用的端口
    for (var k = initPort; k < Global.MaxPort; k++) {
        if (lstIpEndPoints?.FindIndex(_it => _it.Port == k) >= 0) continue;
        if (lstTcpConns?.FindIndex(_it => _it.LocalEndPoint.Port == k) >= 0) continue;
        port = k; initPort = port + 1; break;
    }
    it.Port = port;
    it.AllowTest = true;

    Inbounds4Ray inbound = new() {
        listen = Global.Loopback, port = port,
        protocol = nameof(EInboundProtocol.mixed),
        settings = new Inboundsettings4Ray() { udp = true, auth = "noauth" },
    };
    inbound.tag = inbound.protocol + inbound.port.ToString();   // 例: "mixed10829"
    _coreConfig.inbounds.Add(inbound);

    var tag = Global.ProxyTag + inbound.port.ToString();        // 例: "proxy10829"
    var proxyOutbounds = new CoreConfigV2rayService(context with { Node = item }).BuildAllProxyOutbounds(tag);
    _coreConfig.outbounds.AddRange(proxyOutbounds);

    RulesItem4Ray rule = new() { inboundTag = [inbound.tag], outboundTag = tag, type = "field" };
    _coreConfig.routing.rules.Add(rule);
}
```

来源：<https://raw.githubusercontent.com/2dust/v2rayN/master/v2rayN/ServiceLib/Services/CoreConfig/V2ray/CoreConfigV2rayService.cs>

sing-box 版本同构，只是入站类型为 `mixed`、路由写在 `route.rules`，且先 `_coreConfig.outbounds.RemoveAt(0)`：

```csharp
Inbound4Sbox inbound = new() { listen = Global.Loopback, listen_port = port,
                               type = nameof(EInboundProtocol.mixed) };
inbound.tag = inbound.type + inbound.listen_port.ToString();
_coreConfig.inbounds.Add(inbound);
var tag = Global.ProxyTag + inbound.listen_port.ToString();
_coreConfig.route.rules.Add(new Rule4Sbox { inbound = new List<string> { inbound.tag }, outbound = tag });
```

来源：<https://raw.githubusercontent.com/2dust/v2rayN/master/v2rayN/ServiceLib/Services/CoreConfig/Singbox/CoreConfigSingboxService.cs>

### a.4 客户端侧如何连

```csharp
var webProxy = new WebProxy($"socks5://{Global.Loopback}:{it.Port}");   // 127.0.0.1:{每节点端口}
```

`Global.Loopback = "127.0.0.1"`。入站协议是 `mixed`（同时接受 SOCKS5 与 HTTP 代理），客户端固定按 **SOCKS5** 使用，无认证。

来源：`SpeedtestService.DoRealPing` / `DoSpeedTest`；<https://raw.githubusercontent.com/2dust/v2rayN/master/v2rayN/ServiceLib/Global.cs>

### a.5 端口基址

```csharp
// EInboundProtocol.cs
public enum EInboundProtocol { socks = 0, socks2, socks3, pac, api, api2, mixed, speedtest = 21 }

// AppManager.cs
public int GetLocalPort(EInboundProtocol protocol) {
    var localPort = _config.Inbound.FirstOrDefault(t => t.Protocol == nameof(EInboundProtocol.socks))?.LocalPort ?? 10808;
    return localPort + (int)protocol;
}
```

→ 默认 socks 端口 10808，则测速端口基址 = **10808 + 21 = 10829**。

来源：<https://raw.githubusercontent.com/2dust/v2rayN/master/v2rayN/ServiceLib/Enums/EInboundProtocol.cs>、<https://raw.githubusercontent.com/2dust/v2rayN/master/v2rayN/ServiceLib/Manager/AppManager.cs>

### a.6 单节点路径的端口分配（测速用）

```csharp
// CoreConfigHandler.cs
public static async Task<RetResult> GenerateClientSpeedtestConfig(Config config, CoreConfigContext context, ServerTestItem testItem, string fileName) {
    var initPort = AppManager.Instance.GetLocalPort(EInboundProtocol.speedtest);
    var port = Utils.GetFreePort(initPort + testItem.QueueNum);
    testItem.Port = port;
    ...
}
```

`QueueNum` 是节点在本次选择列表中的序号；`Utils.GetFreePort()` 先探测端口是否被监听/占用，占用则用 `TcpListener(IPAddress.Loopback, 0)` 让系统随机分配一个空闲端口。

单节点生成的配置只有一个入站：

```csharp
_coreConfig.inbounds.Add(new() {
    tag = $"{EInboundProtocol.socks}{port}", listen = Global.Loopback, port = port,
    protocol = nameof(EInboundProtocol.mixed),
    settings = new Inboundsettings4Ray() { udp = true, auth = "noauth" },
});
_coreConfig.routing.domainStrategy = Global.AsIs;
_coreConfig.routing.rules.Add(BuildFinalRule());
```

来源：<https://raw.githubusercontent.com/2dust/v2rayN/master/v2rayN/ServiceLib/Handler/CoreConfigHandler.cs>、<https://raw.githubusercontent.com/2dust/v2rayN/master/v2rayN/ServiceLib/Services/CoreConfig/V2ray/CoreConfigV2rayService.cs>

---

## (b) 各测试类型逐条原理

### b.0 枚举与菜单对应

```csharp
public enum ESpeedActionType { Tcping, Realping, UdpTest, Speedtest, Mixedtest, FastRealping }
```

来源：<https://raw.githubusercontent.com/2dust/v2rayN/master/v2rayN/ServiceLib/Enums/ESpeedActionType.cs>

| 枚举 | UI 菜单（ResUI.resx 英文原文） | 备注 |
|---|---|---|
| `Tcping` | `menuTcpingServer` = "Test tcping" | |
| `Realping` | `menuRealPingServer` = "Test real delay" | |
| `UdpTest` | `menuUdpTestServer` = "Test Configurations UDP Delay" | |
| `Speedtest` | `menuSpeedServer` = "Test download speed" | |
| `Mixedtest` | （工具栏「多测试」按钮） | 真实延迟 + 下载速度 |
| `FastRealping` | `menuFastRealPing` = "Test real delay" | **在 VM 层被改写为 `Realping`** |

```csharp
// ProfilesViewModel.ServerSpeedtest
if (actionType is ESpeedActionType.Mixedtest or ESpeedActionType.FastRealping) {
    if (actionType == ESpeedActionType.FastRealping) { actionType = ESpeedActionType.Realping; }
    lstSelected = JsonUtils.Deserialize<List<ProfileItem>>(JsonUtils.Serialize(ProfileItems?.OrderBy(t => t.Sort)));
}
```

即 **`FastRealping` 等价于「对当前列表全部节点执行 Realping」，与 Test-real-delay 的区别只在于选中的节点集合**（前者无视用户勾选，取当前列表全部）。`SpeedtestService.RunAsync` 的 switch 里**没有** `FastRealping` 分支（已提前改写）。

来源：<https://raw.githubusercontent.com/2dust/v2rayN/master/v2rayN/ServiceLib/ViewModels/ProfilesViewModel.cs>、<https://raw.githubusercontent.com/2dust/v2rayN/master/v2rayN/ServiceLib/Resx/ResUI.resx>

### b.1 Tcping —— 纯 TCP 连接测时，不启动内核

```csharp
private async Task<int> GetTcpingTime(string? url, int port, CancellationToken ct = default)
{
    var responseTime = -1;
    if (url.IsNullOrEmpty() || port <= 0) { return responseTime; }

    if (!IPAddress.TryParse(url, out var ipAddress)) {
        var ipHostInfo = await Dns.GetHostEntryAsync(url, ct);
        ipAddress = ipHostInfo.AddressList.First();          // 只取解析结果第一个地址，无 Happy-Eyeballs
    }
    IPEndPoint endPoint = new(ipAddress, port);
    using Socket clientSocket = new(endPoint.AddressFamily, SocketType.Stream, ProtocolType.Tcp);

    var timer = Stopwatch.StartNew();
    try {
        using var timeoutCts = new CancellationTokenSource(TimeSpan.FromSeconds(5));   // 硬编码 5s
        using var linkedCts = CancellationTokenSource.CreateLinkedTokenSource(ct, timeoutCts.Token);
        await clientSocket.ConnectAsync(endPoint, linkedCts.Token).ConfigureAwait(false);
        responseTime = (int)timer.ElapsedMilliseconds;
    }
    finally { timer.Stop(); }
    return responseTime;
}
```

要点：

- **纯 TCP 三次握手耗时**，不发送任何字节、不做 TLS、不启动 Xray/sing-box。
- 域名先本地 DNS 解析（`Dns.GetHostEntryAsync`），解析出的第一个 IP；**IPv6-only 或解析到不可达 IP 时会误判**。
- 超时 5 秒（硬编码字面量，非配置项）。
- **重要细节（易被误读）：此方法没有 catch。** 连接被拒/超时/DNS 失败的异常会向上抛到 `RunTcpingAsync` 的 `catch (Exception ex) { Logging.SaveLog(_tag, ex); }` 被吞掉并只记日志，**`SetTestDelay` 不会被调用，节点的 Delay 保持旧值**。`-1` 只在「url 为空 / port<=0」两个前置守卫分支返回。

并发（`RunTcpingAsync`）：

```csharp
var pageSize = Math.Min(selecteds.Count, _speedTestPageSize);       // _speedTestPageSize 默认 1000
var lstBatch = GetTestBatchItem(selecteds, pageSize);               // 按 CoreType 分段批处理
foreach (var lst in lstBatch) {
    var parallelOptions = new ParallelOptions { CancellationToken = ct };   // 未设 MaxDegreeOfParallelism
    await Parallel.ForEachAsync(lst, parallelOptions, async (item, innerCt) => { ... });
    await Task.Delay(_delayInterval, ct);                                  // 默认 1s
}
```

- 批大小 = `min(节点数, SpeedTestPageSize=1000)`，且按 `CoreType`（Xray / sing_box）分成两段各自成批。
- **未显式设置 `MaxDegreeOfParallelism`**（.NET `Parallel.ForEachAsync` 的默认并发度由运行时决定，非 v2rayN 常量）。
- 每批之间 sleep `SpeedTestDelayInterval`（默认 1 秒）。
- 结果写入：`ProfileExManager.Instance.SetTestDelay(item.IndexId, responseTime)` + UI 回调。

来源：<https://raw.githubusercontent.com/2dust/v2rayN/master/v2rayN/ServiceLib/Services/SpeedtestService.cs>

### b.2 Realping（真实延迟）—— 通过本地 socks5 入站发 HTTP GET

流程（批量）：`RunRealPingBatchAsync` → 每批 `RunRealPingAsync(lst, ...)` → `CoreManager.LoadCoreConfigSpeedtest(selecteds)` 起**一个内核承载整批** → `await Task.Delay(1000)` → `Parallel.ForEachAsync` 并发跑 `DoRealPing` → finally `processService.StopAsync()`。

`DoRealPing`：

```csharp
var webProxy = new WebProxy($"socks5://{Global.Loopback}:{it.Port}");
var responseTime = await ConnectionHandler.GetRealPingTime(webProxy, ct);
ProfileExManager.Instance.SetTestDelay(it.IndexId, responseTime);
await UpdateFunc(it.IndexId, responseTime.ToString());

if (!_config.UiItem.HideColumnIpInfo && responseTime > 0) {
    var ipInfo = await ConnectionHandler.GetIPInfo(webProxy, ct);      // 查出口 IP / 国家
    ...SetTestIpInfo...
}
```

`ConnectionHandler.GetRealPingTime`：

```csharp
public static async Task<int> GetRealPingTime(IWebProxy? webProxy, CancellationToken cancellationToken = default)
{
    var url = AppManager.Instance.Config.SpeedTestItem.SpeedPingTestUrl;   // 默认 https://www.google.com/generate_204
    var responseTime = -1;
    try {
        using var timeoutCts = new CancellationTokenSource();
        timeoutCts.CancelAfter(Global.LocalFetch);                          // 5 秒
        using var linkedCts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, timeoutCts.Token);
        var linkedToken = linkedCts.Token;
        using var client = new HttpClient(new SocketsHttpHandler() {
            Proxy = webProxy, UseProxy = webProxy != null, ConnectTimeout = Global.LocalFetch,
        });

        List<int> oneTime = [];
        for (var i = 0; i < 2; i++) {
            var timer = Stopwatch.StartNew();
            await client.GetAsync(url, linkedToken).ConfigureAwait(false);
            timer.Stop();
            oneTime.Add((int)timer.Elapsed.TotalMilliseconds);
            await Task.Delay(100, linkedToken);
        }
        responseTime = oneTime.Where(x => x > 0).OrderBy(x => x).FirstOrDefault();   // 取最小值
    }
    catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested) { throw; }
    catch { /* Ignore */ }
    return responseTime;
}
```

逐条回答提问：

- **探测目标**：`SpeedTestItem.SpeedPingTestUrl`，默认 `https://www.google.com/generate_204`（= `Global.SpeedPingTestUrls.First()`）。候选列表共 6 条（见下表）。
- **超时**：单次请求 `Global.LocalFetch = 5s`（`CancelAfter` + `ConnectTimeout` 都是 5s）。
- **延迟毫秒数怎么来的**：`Stopwatch` 包住 `await client.GetAsync(url)`（到响应头返回即返回，未读 body），**连发 2 次、间隔 100ms，取两次中 >0 的最小值**；两次都失败则 `-1`。
- **是否校验响应**：**不校验**。既不看状态码也不看 body 是否为空，哪怕返回 403/502 也计为成功延迟。
- **是否走本地代理入站**：是。`webProxy = socks5://127.0.0.1:{it.Port}`，走当前节点对应的本地 mixed 入站。
- **失败标记**：`catch { }` 兜底 → 返回 `-1`，且 `SetTestDelay` 是无条件调用的 → **`Delay = -1` 会被持久化**。

失败批量重试（`RunRealPingBatchAsync`）：

```csharp
List<ServerTestItem> lstFailed = [];
foreach (var lst in lstTest) {
    var ret = await RunRealPingAsync(lst, completedIds, ct);
    if (ret == false) { lstFailed.AddRange(lst); }         // ret==false 仅当内核起不来
    await Task.Delay(_delayInterval, ct);
}
//Retest the failed part
var pageSizeNext = pageSize / 2;
if (lstFailed.Count > 0 && pageSizeNext > 0) {
    await UpdateFunc("", string.Format(ResUI.SpeedtestingTestFailedPart, lstFailed.Count));
    if (pageSizeNext > _config.SpeedTestItem.MixedConcurrencyCount) {
        await RunRealPingBatchAsync(lstFailed, completedIds, pageSizeNext, ct);   // 批大小减半递归
    } else {
        await RunMixedTestAsync(lstSelected, completedIds, MixedConcurrencyCount, false, ct);
    }
}
```

注意 `RunRealPingAsync` 的返回值语义：**只在 `LoadCoreConfigSpeedtest` 返回 null（内核启动失败）时返回 false**；单个节点超时不算「失败批」。

另一个可用入口 `ConnectionHandler.GetRealPingTimeInfo()`（主链路 `RunAvailabilityCheck` 用，不是节点测试路径）：最多 2 轮、轮间隔 500ms。

`GetIPInfo`（出口 IP / 国家）：URL 取 `SpeedTestItem.IPAPIUrl`，为空直接返回 null；`DownloadService.TryDownloadString` 拉取后按多字段兜底解析：
`ip ?? clientIp ?? ip_addr ?? query`，`country_code ?? country ?? countryCode ?? location?.country_code ?? "unknown"`。

来源：<https://raw.githubusercontent.com/2dust/v2rayN/master/v2rayN/ServiceLib/Handler/ConnectionHandler.cs>

### b.3 UdpTest —— 通过本地 SOCKS5 UDP ASSOCIATE 打 UDP 探测包

`DoUdpTest`：

```csharp
var udpService = UdpTestService.CreateFromTarget(_config?.SpeedTestItem.UdpTestTarget, out var targetServerHost);
var responseTime = (int)(await udpService.SendUdpRequestAsync(targetServerHost, it.Port, ct)).TotalMilliseconds;
ProfileExManager.Instance.SetTestDelay(it.IndexId, responseTime);
```

> 注：任务描述里写的 out 参数名 `udpTestUrl` 不准确，master 中实名为 `targetServerHost`。

`UdpTestService`：

- 目标字符串格式 `类型:主机[:端口]`，`Split(':', 2)`，类型键**忽略大小写**；
- 工厂表 `ntp / dns / stun / mcbe`；**空值或未知键一律回落 `ntp`**（`DefaultUdpTestType = "ntp"`）；
- 默认目标 = `Global.UdpTestTargets.First()` = `ntp:pool.ntp.org`；
- 各 tester 默认 host:port：ntp `pool.ntp.org:123`、dns `8.8.8.8:53`、stun `stun.voztovoice.org:3478`、mcbe `pms.mc-complex.com:19132`。

RTT 测量：

```csharp
using var timeoutCts = new CancellationTokenSource(TimeSpan.FromSeconds(5));   // 总超时 5s
using var channel = new Socks5UdpChannel("127.0.0.1", socks5Port);
if (!await channel.EstablishUdpAssociationAsync(linkedCt)) { throw new Exception("Failed to establish UDP association with SOCKS5 proxy."); }
var roundTripTime = TimeSpan.MaxValue;
for (var attempt = 0; attempt < 2; attempt++) {
    try {
        var stopwatch = new Stopwatch(); stopwatch.Start();
        await channel.SendAsync(targetHost, targetPort, udpRequestPacket, linkedCt);
        var (_, receiveResult) = await channel.ReceiveAsync(linkedCt);
        stopwatch.Stop();
        if (!_udpTest.VerifyAndExtractUdpResponse(receiveResult)) { continue; }   // 校验失败静默重试
        validUdpReceiveResult = receiveResult;
        var currentRoundTripTime = stopwatch.Elapsed;
        if (currentRoundTripTime < roundTripTime) { roundTripTime = currentRoundTripTime; }  // 取最小
    }
    catch (OperationCanceledException) when (timeoutCts.IsCancellationRequested) { throw; }
    catch { if (attempt == 1 && roundTripTime == TimeSpan.MaxValue) { throw; } }
}
if (validUdpReceiveResult != null) { return roundTripTime; }
throw new Exception("Failed to verify and extract UDP response.");
```

- Stopwatch **包住「发送 + 接收」两步**（不含 SOCKS5 握手）；
- 最多 2 次尝试取**最小 RTT**，只有通过校验的响应才计入；
- 总超时 5 秒；
- **失败时 `SendUdpRequestAsync` 抛异常 → `DoUdpTest` 抛出 → `RunUdpTestAsync` 的 `catch` 记录日志，Delay 不更新**（与 Realping 不同，不会写 -1）。

隧穿细节（`Socks5UdpChannel.cs`）：本地 `UdpClient(new IPEndPoint(IPAddress.Any, 0))` 随机端口；TCP 向 `127.0.0.1:{it.Port}` 发 `05 01 00` 握手，再发 CMD `0x03`（UDP ASSOCIATE）+ ATYP=IPv4 `0.0.0.0:0`，从响应解析 relay endpoint 作为 UDP 目标；封装格式 `RSV(2)+FRAG(1)+ATYP/ADDR/PORT+payload`，`FRAG != 0` 抛 `NotSupportedException`。

批量结构与 Realping 相同（1 内核承载整批、`Task.Delay(1000)` 就绪等待、批后 1s 间隔），差异是**失败重试只有一轮，不递归减半**：

```csharp
if (lstFailed.Count > 0) {
    await UpdateFunc("", string.Format(ResUI.SpeedtestingTestFailedPart, lstFailed.Count));
    await RunUdpTestAsync(lstFailed, completedIds, ct);
}
```

来源：<https://raw.githubusercontent.com/2dust/v2rayN/master/v2rayN/ServiceLib.UdpTest/UdpTestService.cs>、<https://raw.githubusercontent.com/2dust/v2rayN/master/v2rayN/ServiceLib.UdpTest/Socks5UdpChannel.cs>、<https://raw.githubusercontent.com/2dust/v2rayN/master/v2rayN/ServiceLib.UdpTest/Tester/IUdpTest.cs>

### b.4 Speedtest / Mixedtest —— 每节点独占内核 + 限时下载取峰值

```csharp
private async Task RunMixedTestAsync(List<ServerTestItem> selecteds,
    ConcurrentDictionary<string, byte> completedIds, int concurrencyCount, bool blSpeedTest, CancellationToken ct = default)
{
    var downloadHandle = new DownloadService();
    var parallelOptions = new ParallelOptions { MaxDegreeOfParallelism = concurrencyCount, CancellationToken = ct };

    await Parallel.ForEachAsync(selecteds, parallelOptions, async (it, innerCt) =>
    {
        innerCt.ThrowIfCancellationRequested();
        ProcessService processService = null;
        try {
            processService = await CoreManager.Instance.LoadCoreConfigSpeedtest(it);   // ← 单节点重载，独立内核
            if (processService is null) { await UpdateFunc(it.IndexId, "", ResUI.FailedToRunCore); return; }
            await Task.Delay(1000, innerCt);

            var delay = await DoRealPing(it, completedIds, innerCt);
            if (blSpeedTest) {
                if (delay > 0) { await DoSpeedTest(downloadHandle, it, completedIds, innerCt); }
                else { await UpdateFunc(it.IndexId, "", ResUI.SpeedtestingSkip); }      // 延迟失败则跳过测速
            }
        } catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
          catch (Exception ex) { Logging.SaveLog(_tag, ex); }
        finally { if (processService != null) { await processService.StopAsync(); } }  // 用完即杀
    });
}
```

调用方传入的并发：

```csharp
case ESpeedActionType.Speedtest:  await RunMixedTestAsync(lstSelected, completedIds, 1, true, ct); break;
case ESpeedActionType.Mixedtest:  await RunMixedTestAsync(lstSelected, completedIds, _config.SpeedTestItem.MixedConcurrencyCount, true, ct); break;
```

→ **「测下载速度」是严格串行的（并发 1，一次一个内核）**；「多测试」并发 = `MixedConcurrencyCount`（默认 5），即**同时最多 5 个内核进程**。

`DoSpeedTest`：

```csharp
var webProxy = new WebProxy($"socks5://{Global.Loopback}:{it.Port}");
var url = _config.SpeedTestItem.SpeedTestUrl;                   // 默认 https://cachefly.cachefly.net/50mb.test
var timeout = _config.SpeedTestItem.SpeedTestTimeout;            // 默认 10（秒）
using var timeoutCts = new CancellationTokenSource(TimeSpan.FromSeconds(timeout));
using var linkedCts = CancellationTokenSource.CreateLinkedTokenSource(ct, timeoutCts.Token);
await downloadHandle.DownloadDataAsync(url, webProxy, async (success, msg) => {
    decimal.TryParse(msg, out var dec);
    if (dec > 0) { ProfileExManager.Instance.SetTestSpeed(it.IndexId, dec); }
    await UpdateFunc(it.IndexId, "", msg);
}, linkedCts.Token);
```

**如何限时中断**：`CancellationTokenSource(TimeSpan.FromSeconds(SpeedTestTimeout))` 与全局 ct 链接，`DownloaderHelper.DownloadDataAsync4Speed` 把这个 token 传给底层下载任务，到点抛出 `OperationCanceledException`（`DownloadService.DownloadDataAsync` 里 `when (cancellationToken.IsCancellationRequested)` 重新抛出）。

下载引擎（`DownloaderHelper.DownloadDataAsync4Speed`，基于 NuGet 包 `Downloader` 5.9.6）：

```csharp
var downloadOpt = new DownloadConfiguration() {
    MaxTryAgainOnFailure = 2,
    RequestConfiguration = requestConfiguration,      // ConnectTimeout = ProxyDownloadConnect = 10s, Proxy = webProxy
    CustomHttpMessageHandlerFactory = () => GetSocketsHttpHandler(requestConfiguration),
};
var lastUpdateTime = DateTime.Now;
var hasValue = false;
double maxSpeed = 0;
await using var downloader = new Downloader.DownloadService(downloadOpt);

downloader.DownloadProgressChanged += (sender, value) => {
    if (!(value.BytesPerSecondSpeed > 0)) { return; }
    hasValue = true;
    if (value.BytesPerSecondSpeed > maxSpeed) { maxSpeed = value.BytesPerSecondSpeed; }
    var ts = DateTime.Now - lastUpdateTime;
    if (ts.TotalMilliseconds >= 1000) {
        lastUpdateTime = DateTime.Now;
        var speed = (maxSpeed / 1000 / 1000).ToString("#0.0");
        onProgress.Invoke(speed);
    }
};
downloader.DownloadFileCompleted += (sender, value) => {
    if (hasValue && maxSpeed > 0) { onProgress.Invoke((maxSpeed / 1000 / 1000).ToString("#0.0")); }
    else if (value.Error != null) { onProgress.Invoke(value.Error?.Message); }
    else { onProgress.Invoke("0"); }
};
await using var stream = await downloader.DownloadFileTaskAsync(address: url, cancellationToken);
```

逐条回答提问：

- **URL**：`SpeedTestItem.SpeedTestUrl`，默认 `https://cachefly.cachefly.net/50mb.test`（`Global.SpeedTestUrls.First()`，候选 7 条，见参数表）。
- **下载多少字节 / 多长时间**：**没有固定字节数、没有固定时长**。实际是「把 URL 下完 **或** 达到 `SpeedTestTimeout`（默认 10s）被取消，先到者为准」。默认 URL 是 50 MB，故默认场景≈「10 秒截断」。
- **速率单位与公式**：`(maxSpeed / 1000 / 1000).ToString("#0.0")` → **十进制 MB/s（1 MB = 1,000,000 B），保留 1 位小数**。`maxSpeed` 是**观测峰值**（`BytesPerSecondSpeed` 的历次最大值，底层单位 B/s），**不是平均速度、不是 MiB/s、不是 bit/s**。UI 列名 `LvSpeed` = "Speed (MB/s)" 佐证。
- **是否有上传速度**：**没有**。全仓库测速路径只做下行。
- **是否支持通过已配置的本地代理来测速**：**不支持**（就「给节点测速」而言）。节点测速固定为每个节点临时起内核 + `socks5://127.0.0.1:{临时端口}`。`DownloadService` 里确实有一套「走主本地代理」的能力（`GetWebProxy(bool blProxy)` → `AppManager.Instance.GetLocalPort(EInboundProtocol.socks)`，并用 SOCKS5 握手探测端口就绪），但它只服务于订阅更新 / 下载 core / 下载 geo 文件（`TryDownloadString(url, blProxy, ...)`），**与节点测速路径无关**。

来源：<https://raw.githubusercontent.com/2dust/v2rayN/master/v2rayN/ServiceLib/Helper/DownloaderHelper.cs>、<https://raw.githubusercontent.com/2dust/v2rayN/master/v2rayN/ServiceLib/Services/DownloadService.cs>

---

## (c) 关键参数默认值表

默认值只在 `ConfigHandler.LoadConfig()` 中赋值（`ConfigItems.cs` 的字段声明**无内联初始化**）：

```csharp
config.SpeedTestItem ??= new();
if (config.SpeedTestItem.SpeedTestTimeout < 10)          // ConfigHandler.cs 行 131
    config.SpeedTestItem.SpeedTestTimeout = 10;          //                行 133
if (config.SpeedTestItem.SpeedTestUrl.IsNullOrEmpty())   //                行 135
    config.SpeedTestItem.SpeedTestUrl = Global.SpeedTestUrls.First();      // 行 137
if (config.SpeedTestItem.SpeedPingTestUrl.IsNullOrEmpty())                 // 行 139
    config.SpeedTestItem.SpeedPingTestUrl = Global.SpeedPingTestUrls.First(); // 行 141
if (config.SpeedTestItem.MixedConcurrencyCount < 1)                        // 行 143
    config.SpeedTestItem.MixedConcurrencyCount = 5;                        // 行 145
if (config.SpeedTestItem.UdpTestTarget.IsNullOrEmpty())                    // 行 147
    config.SpeedTestItem.UdpTestTarget = Global.UdpTestTargets.First();    // 行 149
```

来源：<https://raw.githubusercontent.com/2dust/v2rayN/master/v2rayN/ServiceLib/Handler/ConfigHandler.cs>（行号取自本次抓取落盘副本）

| 参数 | 默认值 | 来源文件 / 位置 |
|---|---|---|
| `SpeedTestItem.SpeedTestPingUrl` 探测 URL | `https://www.google.com/generate_204` | `Global.cs` `SpeedPingTestUrls[0]`；`ConfigHandler.LoadConfig` 行 139–141 |
| `SpeedPingTestUrls` 全部候选 | `https://www.google.com/generate_204`、`https://www.youtube.com/generate_204`、`https://www.googlevideo.com/generate_204`、`https://www.gstatic.com/generate_204`、`https://www.apple.com/library/test/success.html`、`http://www.msftconnecttest.com/connecttest.txt` | `Global.cs` <https://raw.githubusercontent.com/2dust/v2rayN/master/v2rayN/ServiceLib/Global.cs> |
| 真实延迟单次超时 | **5 秒**（`Global.LocalFetch = TimeSpan.FromSeconds(5)`，同时作为 `CancelAfter` 与 `ConnectTimeout`） | `Global.cs`；`ConnectionHandler.GetRealPingTime` |
| 真实延迟请求次数 / 取值 | **2 次，间隔 100ms，取最小** | `ConnectionHandler.GetRealPingTime` |
| Tcping 超时 | **5 秒**（源码内硬编码 `TimeSpan.FromSeconds(5)`，非配置项） | `SpeedtestService.GetTcpingTime` |
| UDP 测试总超时 | **5 秒**（硬编码 `CancellationTokenSource(TimeSpan.FromSeconds(5))`） | `ServiceLib.UdpTest/UdpTestService.cs` |
| UDP 尝试次数 / 取值 | **2 次，取最小 RTT** | 同上 |
| `SpeedTestItem.SpeedTestUrl` 测速 URL | `https://cachefly.cachefly.net/50mb.test` | `Global.cs` `SpeedTestUrls[0]`；`ConfigHandler.LoadConfig` 行 135–137 |
| `SpeedTestUrls` 全部候选 | `https://cachefly.cachefly.net/50mb.test`、`.../100mb.test`、`.../1mb.test`、`.../10mb.test`、`https://speed.cloudflare.com/__down?bytes=10000000`、`...?bytes=50000000`、`...?bytes=99999999` | `Global.cs` |
| `SpeedTestItem.SpeedTestTimeout` | **10**（秒）；`<10` 时强制为 10 | `ConfigHandler.LoadConfig` 行 131–133 |
| 测速下载判定 | 无固定字节数，直到下完或 10s 超时；速率 = 峰值 B/s ÷ 1e6，1 位小数 MB/s | `DownloaderHelper.DownloadDataAsync4Speed` |
| 下载连接超时 / 重试 | `Global.ProxyDownloadConnect = 10s`；`MaxTryAgainOnFailure = 2` | `Global.cs`；`DownloaderHelper` |
| `SpeedTestItem.MixedConcurrencyCount` | **5**；`<1` 时强制为 5（"多测试"并发内核数） | `ConfigHandler.LoadConfig` 行 143–145 |
| `SpeedTestItem.UdpTestTarget` | `ntp:pool.ntp.org` | `Global.UdpTestTargets[0]`；`ConfigHandler.LoadConfig` 行 147–149 |
| `UdpTestTargets` 全部候选 | `ntp:pool.ntp.org`、`ntp:time.google.com`、`dns:1.1.1.1`、`dns:8.8.8.8`、`dns:dns.google`、`stun:stun.voztovoice.org`、`stun:stun.cloudflare.com`、`stun:stun.l.google.com:19302`、`mcbe:pms.mc-complex.com`、`mcbe:bedrock.opblocks.com`、`mcbe:opsucht.net`、`mcbe:play.craftersmc.net`、`mcbe:mps.lemoncloud.net`、`mcbe:bedrock.talonmc.net` | `Global.cs` |
| `SpeedTestItem.IPAPIUrl` | **`LoadConfig` 中无任何默认赋值**（新配置为 null/空 → `GetIPInfo` 直接返回 null，不查 IP）。候选列表 `Global.IPAPIUrls[0] = "https://api.ip.sb/geoip"` | `ConfigHandler.LoadConfig`（读遍 SpeedTestItem 段）；`Global.cs` |
| `SpeedTestItem.SpeedTestPageSize` | `null` → 回退 `Global.SpeedTestPageSize = 1000` | `Global.cs`；`SpeedtestService` |
| `SpeedTestItem.SpeedTestDelayInterval` | `null` → 回退 **1 秒**（每批之间的间隔） | `SpeedtestService` `_delayInterval` |
| 内核就绪等待（测试路径） | **固定 `await Task.Delay(1000)`（盲等 1 秒）** | `SpeedtestService` 三处 |
| `Global.Loopback` | `"127.0.0.1"` | `Global.cs` |
| 测速入站端口基址 | `GetLocalPort(EInboundProtocol.speedtest)` = socks 端口(默认 10808) + **21** = 10829 | `EInboundProtocol.cs`（`speedtest = 21`）；`AppManager.GetLocalPort` |
| 测速配置文件名 | `configTest{0}.json`（`{0}` 为随机 GUID），位于 `binConfigs/` | `Global.CoreSpeedtestConfigFileName`；`Utils.GetBinConfigPath` |
| 主配置 / 前置配置文件名 | `config.json` / `configPre.json` | `Global.cs` |
| 核心启动参数 | Xray：`run -c {0}`；sing-box：`run -c {0} --disable-color` | `CoreInfoManager.InitCoreInfo()` |

---

## (d) 内核生命周期管理

### d.1 配置生成与落盘位置

```csharp
// CoreManager.LoadCoreConfigSpeedtest(List<ServerTestItem>) —— 批量
var coreType = selecteds.FirstOrDefault()?.CoreType == ECoreType.sing_box ? ECoreType.sing_box : ECoreType.Xray;
var fileName = string.Format(Global.CoreSpeedtestConfigFileName, Utils.GetGuid(false));   // "configTest{随机}.json"
var configPath = Utils.GetBinConfigPath(fileName);
var result = await CoreConfigHandler.GenerateClientSpeedtestConfig(_config, configPath, selecteds, coreType);
...
var coreInfo = CoreInfoManager.Instance.GetCoreInfo(coreType);
return await RunProcess(coreInfo, fileName, true, false);
```

- 目录：`Utils.GetBinConfigPath()` = `{StartupPath()}/binConfigs/`（可移植模式下即程序目录，否则 `%LOCALAPPDATA%\v2rayN`）。
- 文件名每次都是新 GUID → **同名冲突不可能**，但也意味着**每次测试都会新增一个文件**。
- 生成方式：读取内嵌样本 `SampleClientConfig` / `SingboxSampleClientConfig` → 反序列化 → `GenLog()` + 清空 inbounds/outbounds/rules → 逐节点追加入站/出站/规则 → 写盘（`File.WriteAllTextAsync`）。

来源：<https://raw.githubusercontent.com/2dust/v2rayN/master/v2rayN/ServiceLib/Manager/CoreManager.cs>、<https://raw.githubusercontent.com/2dust/v2rayN/master/v2rayN/ServiceLib/Handler/CoreConfigHandler.cs>

### d.2 启动

```csharp
private async Task<ProcessService?> RunProcessNormal(string fileName, CoreInfo? coreInfo, string configPath, bool displayLog) {
    var environmentVars = new Dictionary<string, string>();
    foreach (var kv in coreInfo.Environment)
        environmentVars[kv.Key] = string.Format(kv.Value, coreInfo.AbsolutePath ? Utils.GetBinConfigPath(configPath).AppendQuotes() : configPath);

    var procService = new ProcessService(
        fileName: fileName,                                     // bin/{coreType}/xray.exe | sing-box.exe
        arguments: string.Format(coreInfo.Arguments, ...),      // "run -c configTestXXX.json"
        workingDirectory: Utils.GetBinConfigPath(),             // binConfigs/
        displayLog: displayLog, redirectInput: false,
        environmentVars: environmentVars, updateFunc: _updateFunc);

    await procService.StartAsync();
    await Task.Delay(100);
    if (procService is null or { HasExited: true }) { throw new Exception(ResUI.FailedToRunCore); }
    AddProcessJob(procService.Handle);                          // Windows: Job Object 兜底
    return procService;
}
```

- 环境变量：Xray 注入 `XRAY_LOCATION_ASSET` / `XRAY_LOCATION_CERT` = `bin/`；v2fly 注入 `V2RAY_LOCATION_ASSET`。
- `CreateNoWindow = true`、stdout/stderr 重定向并在 `displayLog` 时逐行回调到日志窗口（`ProcessService.RegisterEventHandlers`）。
- **Windows 上把进程句柄加入 Job Object**（`WindowsJobService`），保证 GUI 被强杀时内核不会变孤儿。

来源：`CoreManager.cs`、<https://raw.githubusercontent.com/2dust/v2rayN/master/v2rayN/ServiceLib/Services/ProcessService.cs>、<https://raw.githubusercontent.com/2dust/v2rayN/master/v2rayN/ServiceLib/Manager/CoreInfoManager.cs>

### d.3 就绪判定 —— 测试路径与主链路完全不同

**测试路径（SpeedtestService）：没有端口探测，只有盲等。**

```csharp
await Task.Delay(1000, ct);          // RunRealPingAsync  / RunUdpTestAsync
await Task.Delay(1000, innerCt);     // RunMixedTestAsync
```

唯一的「启动成功」判定发生在 `RunProcessNormal`：启动后 `await Task.Delay(100)`，若进程已退出则抛 `FailedToRunCore`。也就是说 v2rayN 判定就绪 = **100ms 后进程还活着 + 再睡 1000ms**。

**主链路（CoreManager.LoadCore，非测试）：用 SOCKS5 握手探测端口。** 两处等价实现：

```csharp
// DownloadService.SocksPortCheck / CoreManager.WaitForProxyPort
using var rootTimeOutCts = new CancellationTokenSource(Global.LocalFetch);   // 总超时 5s
ReadOnlyMemory<byte> greeting = new byte[] { 0x05, 0x01, 0x00 };             // SOCKS5: VER=5, NMETHODS=1, METHOD=no-auth
var buf = new byte[2];
while (!rootToken.IsCancellationRequested) {
    using var tcp = new TcpClient();
    using var attemptCts = new CancellationTokenSource(TimeSpan.FromMilliseconds(50));
    ...
    await tcp.ConnectAsync(ip, port, linkedToken);
    await stream.WriteAsync(greeting, linkedToken);
    var read = await stream.ReadAsync(buf.AsMemory(0, 2), linkedToken);
    if (read == 2 && buf[0] == 0x05) { return true; }        // 收到 05 00 即就绪
    ...
    catch (SocketException ex) when (ex.SocketErrorCode == SocketError.ConnectionRefused) {
        await Task.Delay(50, rootToken);                      // 每 50ms 重试
    }
}
```

`WaitForProxyPort` 仅在 `preContext.IsTunEnabled` 时启用；主链路另有 `await Task.Delay(1000)` + `ConnectionHandler.RunAvailabilityCheck()`（真实延迟 + 出口 IP）作为可用性确认。

来源：`DownloadService.cs`、`CoreManager.cs`、`MainWindowViewModel.Reload()`

### d.4 退出清理

- **进程**：`finally { if (processService != null) await processService.StopAsync(); }`，`StopAsync` 先 `CancelOutputRead/CancelErrorRead`，非 Windows 再 `Kill(true)`（连子进程），然后 `Kill()`，最后 `await Task.Delay(100)`。主链路 `CoreManager.CoreStop()` 额外 `Dispose()` 并置 null。
- **配置文件**：**没有发现任何清理逻辑。** 检索过的位置包括 `App.xaml.cs`（OnStartup/OnExit）、`MainWindowViewModel.cs`（Init/Reload/OnExit 链路）、`CoreManager.cs`、`CoreConfigHandler.cs`、`ProcessService.cs` —— 均未删除 `configTest*.json`。`FileUtils.DeleteExpiredFiles(sourceDir, dtLine, contains)` 存在，但未能在已读文件中找到调用点（GitHub 代码搜索不可用）。→ 见「未确证项」。

来源：`ProcessService.StopAsync`、`CoreManager.CoreStop`、<https://raw.githubusercontent.com/2dust/v2rayN/master/v2rayN/v2rayN/App.xaml.cs>

### d.5 测试结果的回写路径

```
SpeedtestService.UpdateFunc(indexId, delay, speed)
   → SpeedtestResult { IndexId, Delay, Speed, IpInfo }        // 回调到 ProfilesViewModel.SetSpeedTestResult
   → UI: item.Delay / item.DelayVal / item.SpeedVal / item.IpInfo
   → 持久化: ProfileExManager.SetTestDelay / SetTestSpeed / SetTestMessage / SetTestIpInfo
             （写入内存 ConcurrentBag + 入队 indexId，结束时 SaveTo() 批量落 SQLite）
```

`ProfileExItem`（表名 `ProfileExItem`，SQLite）：`IndexId`(PK) / `Delay`(int) / `Speed`(decimal) / `Sort`(int) / `Message`(string) / `IpInfo`(string)。

UI 映射（`ProfilesViewModel.GetProfileItemsEx`）：

```csharp
Delay    = t33?.Delay ?? 0,
Speed    = t33?.Speed ?? 0,
DelayVal = t33?.Delay != 0 ? $"{t33?.Delay}" : string.Empty,          // 延迟列：0 不显示（含 -1 会显示 "-1"）
SpeedVal = t33?.Speed > 0 ? $"{t33?.Speed}" : t33?.Message ?? string.Empty,  // 速度列为 0 时显示 Message 文案
IpInfo   = t33?.IpInfo ?? string.Empty,
```

**失败 / 超时 / 不可达的标记方式（逐类型）**：

| 场景 | 行为 | 是否落库 |
|---|---|---|
| Realping 全部失败/超时 | `GetRealPingTime` 的 `catch{}` → **`Delay = -1`**，`SetTestDelay(-1)` 无条件调用 | ✅ 写 -1 |
| Tcping 连接失败/超时/DNS 失败 | 异常逃出 `GetTcpingTime` → `RunTcpingAsync` 捕获仅记日志 → **Delay 不更新（保留旧值）** | ❌ 不写 |
| Tcping 前置守卫（address 空 / port<=0） | 返回 -1 | ✅ 写 -1 |
| UdpTest 失败/超时 | `SendUdpRequestAsync` 抛异常 → 捕获仅记日志 → **Delay 不更新** | ❌ 不写 |
| 节点被判定不可测（`AllowTest == false`，如协议与内核不匹配） | `UpdateFunc(indexId, ResUI.SpeedtestingSkip)`（Delay 槽位塞文案）→ UI 显示 "Skip test" | ❌ 不写 |
| Speedtest：真实延迟 ≤ 0 | `UpdateFunc(indexId, "", ResUI.SpeedtestingSkip)` → Message="Skip test" | ✅ 写 Message（因 speed 参数非空） |
| Speedtest：内核起不来 | `UpdateFunc(indexId, "", ResUI.FailedToRunCore)` = "Failed to run Core, please check the prompt information" 并 `return` | ✅ 写 Message |
| 测速失败（异常文本非数字） | `decimal.TryParse` 失败 → `SetTestSpeed` 不调用，但 `SetTestMessage(errMsg)` 会写入 | ✅ 写 Message |
| 全局取消（Stop/ESC） | `SetTestResultAsync(未完成项, ResUI.SpeedtestingSkip)` → "Skip test" | ❌ 不写（Delay 槽位） |

**专用的「无效节点」判定**（`ConfigHandler.RemoveInvalidServerResult`）：

```csharp
var lstProfile = (from t in lstModel
                  join t2 in lstProfileExs on t.IndexId equals t2.IndexId
                  where t2.Delay == -1
                  select t).ToList();
await RemoveServers(config, ...);
```

即 **`Delay == -1` 是唯一的「无效/超时」哨兵**；源码中**不存在 9999**。因此只有 Realping 的结果能驱动「移除无效节点」——Tcping/UdpTest 失败不会产生可用的 invalid 标记（这是 v2rayN 的一个内部不一致，值得在自研面板中修正）。

TaskResult 起点状态：每次测试开始先 `SetTestResultAsync(lstSelected, actionType, ResUI.Speedtesting)`，其中 `Speedtesting` = **"Testing..."**，按类型塞入 Delay 槽（Tcping/Realping/UdpTest）、Speed 槽（Speedtest）或两者（Mixedtest）。

其他 UI 文案（`ResUI.resx` 英文原文）：`SpeedtestingStop`="Test terminating..."、`SpeedtestingCompleted`="Test completed"、`SpeedtestingSkip`="Skip test"、`SpeedtestingPressEscToExit`="Press ESC to terminate the test"、`SpeedtestingTestFailedPart`="Starting retesting failed parts, {0} remaining. Press ESC to terminate..."、`FailedToRunCore`="Failed to run Core, please check the prompt information"、`LvSpeed`="Speed (MB/s)"。

**取消机制**：`SpeedtestService` 持有 `_runCts`，`RunLoop` 每次先 `_runCts?.Cancel()` 再建新的 linked CTS；`ExitLoop()` 取消当前测试（UI 由 ESC 触发）。取消后所有 `Parallel.ForEachAsync` 中断，`finally` 里 `ProfileExManager.SaveTo()` + 提示 "Test completed"。注意：**测速中途取消时，`finally` 中 `processService.StopAsync()` 会杀掉当前内核**，不会泄漏。

来源：<https://raw.githubusercontent.com/2dust/v2rayN/master/v2rayN/ServiceLib/Services/SpeedtestService.cs>、<https://raw.githubusercontent.com/2dust/v2rayN/master/v2rayN/ServiceLib/Manager/ProfileExManager.cs>、<https://raw.githubusercontent.com/2dust/v2rayN/master/v2rayN/ServiceLib/Handler/ConfigHandler.cs>、<https://raw.githubusercontent.com/2dust/v2rayN/master/v2rayN/ServiceLib/ViewModels/ProfilesViewModel.cs>、<https://raw.githubusercontent.com/2dust/v2rayN/master/v2rayN/ServiceLib/Resx/ResUI.resx>

---

## (e) 可直接借鉴的设计要点（面向 Java 21 / Spring Boot 无前端构建面板，测 vless/vmess）

1. **批量延迟测试用「一个内核 + 每节点一个 mixed 入站端口 + inboundTag→outboundTag 路由」，绝不每节点起进程。**
   具体：socks 基端口 10808，测速端口从 `10808+21=10829` 起向上取空闲端口；为每个节点生成
   `{"tag":"mixed{P}","listen":"127.0.0.1","port":P,"protocol":"mixed","settings":{"auth":"noauth","udp":true}}`
   ＋一条 `{"type":"field","inboundTag":["mixed{P}"],"outboundTag":"proxy{P}"}`，出站 `proxy{P}` 即该节点。
   各节点互不干扰、天然并行，100 个节点仍是 1 个 Xray 进程。
   （v2rayN 源码：`CoreConfigV2rayService.GenerateClientSpeedtestConfig(List<ServerTestItem>)`）

2. **不要抄 v2rayN 的「盲等 1 秒」就绪判定。** 它在测速路径上只做 `await Task.Delay(1000)`；只有主链路才做端口探测。Java 侧应主动探测：
   连 `127.0.0.1:{P}`，写 3 字节 `05 01 00`，读 2 字节且 `b[0]==0x05` 即就绪；总超时 5s，`ConnectionRefused` 时每 50ms 重试。算法可直接照搬 v2rayN 的 `SocksPortCheck` / `WaitForProxyPort`。

3. **真实延迟的探测目标与期望**：默认用 `https://www.gstatic.com/generate_204`（或 v2rayN 默认的 `https://www.google.com/generate_204`），**期望 204 且 body 为空**。注意 v2rayN 实际上**完全不校验状态码**，只测 `HttpClient.GetAsync` 耗时——建议比它更严格：`status == 204 && body.isEmpty()`，否则视为失败。单次超时 **5 秒**（`Global.LocalFetch`），同时设为 `ConnectTimeout`。

4. **延迟取「连测 2 次、间隔 100ms、取最小值」**，任一成功即用，两次都失败返回 `-1`。这一条几乎可以逐行翻译成 Java（`HttpClient` + `System.nanoTime()`）。用 `-1` 而不是抛异常，是因为要区分「数字型结果」与「文案型状态」。

5. **`-1` 作为唯一的 invalid 哨兵，并配一个「一键移除无效节点」。** v2rayN：`WHERE Delay = -1` 删节点（`ConfigHandler.RemoveInvalidServerResult`）。**不要用 9999**（v2rayN 源码里根本不存在）。关键改进点：v2rayN 只有 Realping 会写 -1，Tcping/UdpTest 失败因异常逃逸而**不写**；Java 侧应在 `catch` 里统一写 `-1`，保证「测试过」和「没测过（旧值）」可区分。

6. **Tcping 作为零成本快筛，先淘汰死节点再跑真实延迟。** 纯 `SocketChannel`/`Socket.connect(addr, port)` 计时，超时 5 秒，不启动内核（1000 个节点几秒内出结果）。注意 v2rayN 的坑：只取 `Dns.GetHostEntryAsync` 解析出的**第一个 IP**，没有 Happy Eyeballs，IPv6 优先时会大量假死——Java 侧应遍历全部解析结果并取最快成功者。

7. **测速不要照抄「峰值 MB/s」。** v2rayN 取的是观测到的**峰值** `maxSpeed`，公式 `峰值字节/秒 ÷ 1_000_000`，保留 1 位小数，单位 **MB/s（十进制，非 MiB/s）**。峰值受 TCP 慢启动后的瞬时抖动影响很大，可复现性差；建议改为取「去掉首秒后的平均值」或「P50」，并同时在 UI 标注单位。
   参考参数：URL `https://cachefly.cachefly.net/50mb.test` 或 `https://speed.cloudflare.com/__down?bytes=50000000`；**限时 10 秒**（`SpeedTestTimeout`，v2rayN 强制下限 10），到点 `cancel` 中断；**没有上传测速**。

8. **测速必须「先真实延迟、再下载」，延迟失败直接跳过。** v2rayN：`if (delay > 0) DoSpeedTest() else skip`。这一条能省掉大量无谓等待。

9. **测速阶段的并发要显式限流且远低于延迟阶段**：v2rayN 「测下载速度」严格串行（并发 1），「多测试」并发 5（`MixedConcurrencyCount`），且测速是**每节点独立内核**（因为要独占带宽、避免互相干扰）。Java 侧建议：延迟测试 16–32 并发（共享 1 内核），测速 1–5 并发（每节点独立内核），每批之间 `sleep 1s`（`SpeedTestDelayInterval` 默认 1s）。

10. **补上 UDP 可用性测试（多数自研面板都缺）。** 方法：通过本地 mixed 入站的 **SOCKS5 UDP ASSOCIATE** 走 UDP——
    TCP 发 `05 01 00` → 收 `05 00` → 发 `05 03 00 01 00 00 00 00 00 00` → 读响应得到 relay 地址；再发一个协议探测包。
    推荐 NTP 探测：48 字节，`req[0] = 0x23`，校验 `resp.length >= 48 && (resp[0] & 0x07) == 4`；或 DNS 查询 `1.1.1.1:53`。
    RTT 用 `System.nanoTime()` 包住「send + receive」两步，最多 2 次取最小，**总超时 5 秒**。这条能识别「TCP 通但 UDP 被墙/QoS」的节点。

11. **内核进程管理照抄这一套**：Xray 参数 `run -c {configPath}`（sing-box 为 `run -c {cfg} --disable-color`），**工作目录设成配置目录**、传相对文件名；`redirectErrorStream(true)` 把内核日志接到面板日志；退出时 `destroyForcibly()` + `waitFor(2s)`；注册 JVM shutdown hook 兜底。v2rayN 在 Windows 上额外用 **Job Object** 保证 GUI 被杀时内核不残留——Java 侧对应的是 `ProcessHandle.descendants()` 清理或独立的 process group。
    **v2rayN 的缺陷值得修正**：它把测速配置写成 `binConfigs/configTest{随机GUID}.json` 且**从不删除**，长期使用会堆积上千个文件。Java 侧应写进临时目录（如 `Files.createTempFile("test-", ".json")`）并在 `finally` 中删除。

12. **把「探测 URL、超时、并发」全部做成可配置项，默认值照抄即可**：
    `SpeedPingTestUrl = https://www.google.com/generate_204`、`SpeedTestUrl = https://cachefly.cachefly.net/50mb.test`、`SpeedTestTimeout = 10s`、`LocalFetch(延迟超时) = 5s`、`MixedConcurrencyCount = 5`、`SpeedTestDelayInterval = 1s`、`IPAPIUrl`（查出口 IP/地区，v2rayN 默认留空，建议默认填 `https://api.ip.sb/geoip`）、`UdpTestTarget = ntp:pool.ntp.org`。
    解析出口 IP 时做多字段兜底（v2rayN 的做法）：`ip ?? clientIp ?? ip_addr ?? query`，国家 `country_code ?? country ?? countryCode ?? location.country_code`。

---

## (f) 未能确证项

1. **`configTest*.json` 是否在别处被清理** —— 已读 `App.xaml.cs`、`MainWindowViewModel.cs`、`CoreManager.cs`、`CoreConfigHandler.cs`、`ProcessService.cs`、`ConfigHandler.cs`（部分，文件 106KB 被截断），均未见删除逻辑；`FileUtils.DeleteExpiredFiles` 有定义但未找到调用点。GitHub 代码搜索（需鉴权）与 grep.app 均不可用，故**不能断言「全局无清理」**，只能说「在已读文件中未发现」。
2. **`SpeedTestItem.IPAPIUrl` 在发行版中是否预置** —— 已逐行读完 `ConfigHandler.LoadConfig()` 的 SpeedTestItem 段落，**其中确实没有给 IPAPIUrl 赋默认值**；但官方发布包内是否附带一个预置了该字段的 `guiNConfig.json` 未验证。
3. **`Parallel.ForEachAsync` 未设 `MaxDegreeOfParallelism` 时的确切并发数** —— 这是 .NET 运行时行为（`ParallelOptions` 默认 -1，实现按可用处理器数派生），**不是 v2rayN 源码中的常量**；未在 v2rayN 仓库内找到证据。
4. **历史版本是否用过 `Delay = 9999`** —— master 全流程只找到 `-1`，源码中无 `9999`。旧版本可能不同，未回溯验证。
5. **内嵌样本配置的完整内容** —— `Global.V2raySampleClient`、`Global.SingboxSampleClient`、`Global.V2raySampleOutbound`、`Global.SingboxSampleOutbound` 指向 `ServiceLib/Sample/*` 资源，本次未读取其正文，故测速配置中 log 级别、DNS、`inbounds/outbounds` 的初始模板细节未确证。
6. **底层下载库行为的版本一致性** —— `Directory.Packages.props` 固定 `Downloader` **5.9.6**；仅 `Bandwidth.cs`（决定 `BytesPerSecondSpeed` 单位 = B/s）做了 v5.9.6 与 master 的比对，其余库文件（`ChunkDownloader.cs` / `DownloadConfiguration.cs` / `SocketClient.cs`）只读到 master，未逐版校验（`BufferBlockSize` 默认 1024、`ChunkCount` 默认 1、`HttpCompletionOption.ResponseHeadersRead` 等按 master 记录）。
7. **`SpeedtestService` 是否有其它调用入口（例如定时任务自动测速）** —— 只在 `ProfilesViewModel.ServerSpeedtest` 找到调用；`TaskManager.cs` 等未逐行读完，自动测速链路未确证。
8. **行号** —— 除 `ConfigHandler.cs`（行 131/133/135/137/139/141/143/145/147/149，来自本次抓取落盘副本）外，raw 抓取不带行号，故其余结论均以「文件 + 方法名 + URL」定位。
