# v2rayN（master）测速下载引擎 与 UDP 测试 源码核查报告

核查分支：`master`（raw.githubusercontent.com 实际抓取，2025 会话内）。
所有结论均来自下面给出的原始 URL；无法读到实物的点已标「未确证」。
行号说明：本报告无法在 raw 文本中获得行号（本地下载被 TLS 阻断），故以 **raw URL** 作为引用；仅 `ConfigHandler.cs` 一行给出确切行号，来自抓取结果的落盘副本（含行号）。

---

## 一、测速下载引擎

### 1.1 入口链路

- `v2rayN/ServiceLib/Services/DownloadService.cs` → `DownloadDataAsync` 调 `DownloaderHelper.Instance.DownloadDataAsync4Speed(webProxy, url, OnProgress, cancellationToken)`，`OnProgress` 只是 `updateFunc.Invoke(false, $"{message}")`。
  https://raw.githubusercontent.com/2dust/v2rayN/master/v2rayN/ServiceLib/Services/DownloadService.cs
- 调用方 `v2rayN/ServiceLib/Services/SpeedtestService.cs` → `DoSpeedTest`：
  ```csharp
  var webProxy = new WebProxy($"socks5://{Global.Loopback}:{it.Port}");
  var url = _config.SpeedTestItem.SpeedTestUrl;
  var timeout = _config.SpeedTestItem.SpeedTestTimeout;
  using var timeoutCts = new CancellationTokenSource(TimeSpan.FromSeconds(timeout));
  ...
  await downloadHandle.DownloadDataAsync(url, webProxy, async (success, msg) =>
  {
      decimal.TryParse(msg, out var dec);
      if (dec > 0) { ProfileExManager.Instance.SetTestSpeed(it.IndexId, dec); }
      await UpdateFunc(it.IndexId, "", msg);
  }, linkedCt);
  ```
  https://raw.githubusercontent.com/2dust/v2rayN/master/v2rayN/ServiceLib/Services/SpeedtestService.cs

### 1.2 `DownloadDataAsync4Speed` 完整方法体（逐字）

文件：`v2rayN/ServiceLib/Helper/DownloaderHelper.cs`
https://raw.githubusercontent.com/2dust/v2rayN/master/v2rayN/ServiceLib/Helper/DownloaderHelper.cs

```csharp
public async Task DownloadDataAsync4Speed(IWebProxy webProxy, string url, Action<string> onProgress, CancellationToken cancellationToken = default)
{
    if (url.IsNullOrEmpty())
    {
        throw new ArgumentNullException(nameof(url));
    }

    var requestConfiguration = new RequestConfiguration()
    {
        ConnectTimeout = GetConnectTimeoutMs(true),
        Proxy = webProxy
    };
    var downloadOpt = new DownloadConfiguration()
    {
        MaxTryAgainOnFailure = 2,
        RequestConfiguration = requestConfiguration,
        CustomHttpMessageHandlerFactory = () => GetSocketsHttpHandler(requestConfiguration),
    };

    var lastUpdateTime = DateTime.Now;
    var hasValue = false;
    double maxSpeed = 0;
    await using var downloader = new Downloader.DownloadService(downloadOpt);

    downloader.DownloadProgressChanged += (sender, value) =>
    {
        if (!(value.BytesPerSecondSpeed > 0))
        {
            return;
        }
        hasValue = true;
        if (value.BytesPerSecondSpeed > maxSpeed)
        {
            maxSpeed = value.BytesPerSecondSpeed;
        }

        var ts = DateTime.Now - lastUpdateTime;
        if (ts.TotalMilliseconds >= 1000)
        {
            lastUpdateTime = DateTime.Now;
            var speed = (maxSpeed / 1000 / 1000).ToString("#0.0");
            onProgress.Invoke(speed);
        }
    };
    downloader.DownloadFileCompleted += (sender, value) =>
    {
        if (hasValue && maxSpeed > 0)
        {
            var finalSpeed = (maxSpeed / 1000 / 1000).ToString("#0.0");
            onProgress.Invoke(finalSpeed);
        }
        else if (value.Error != null)
        {
            onProgress.Invoke(value.Error?.Message);
        }
        else
        {
            onProgress.Invoke("0");
        }
    };
    //progress.Invoke("......");
    await using var stream = await downloader.DownloadFileTaskAsync(address: url, cancellationToken);
}
```

关键点：

1. **没有固定字节数、没有 `Stopwatch`**。时间基准是 `DateTime.Now`（`var lastUpdateTime = DateTime.Now;`），节流阈值为 **1000 ms**，仅用于「多久回调一次 onProgress」。
2. 下载时长/下载量由**外部**决定：调用方的 `CancellationTokenSource(TimeSpan.FromSeconds(SpeedTestTimeout))` 取消，或整段 URL 下载完成。
   默认 URL / 超时（`v2rayN/ServiceLib/Handler/ConfigHandler.cs`，`LoadConfig()`）：
   ```csharp
   if (config.SpeedTestItem.SpeedTestTimeout < 10) { config.SpeedTestItem.SpeedTestTimeout = 10; }   // 第 133 行附近
   if (config.SpeedTestItem.SpeedTestUrl.IsNullOrEmpty()) { config.SpeedTestItem.SpeedTestUrl = Global.SpeedTestUrls.First(); }  // 第 137 行
   ...
   if (config.SpeedTestItem.UdpTestTarget.IsNullOrEmpty()) { config.SpeedTestItem.UdpTestTarget = Global.UdpTestTargets.First(); } // 第 147–149 行
   ```
   `Global.SpeedTestUrls.First()` = `https://cachefly.cachefly.net/50mb.test`（50 MB 文件）。故默认是「**10 秒或 50 MB 先到者为准**」。
   https://raw.githubusercontent.com/2dust/v2rayN/master/v2rayN/ServiceLib/Handler/ConfigHandler.cs
   https://raw.githubusercontent.com/2dust/v2rayN/master/v2rayN/ServiceLib/Global.cs
3. **没有预热请求**（方法内无额外 HEAD/GET）。但同一次混合测速流程在进入 `DoSpeedTest` 前有 `await Task.Delay(1000, innerCt)`，且先执行 `DoRealPing` → `ConnectionHandler.GetRealPingTime`（用 `SpeedPingTestUrl` 连发 2 次 `client.GetAsync`），客观上起到了预热作用。
   https://raw.githubusercontent.com/2dust/v2rayN/master/v2rayN/ServiceLib/Handler/ConnectionHandler.cs
4. `v2rayN` 侧**未设置** `BufferBlockSize`、`ChunkCount`、`ParallelDownload`、`HttpCompletionOption`；只设了 `MaxTryAgainOnFailure = 2`、`ConnectTimeout`、`Proxy` 和自定义 `SocketsHttpHandler`。

### 1.3 速度公式与单位（精确）

`value.BytesPerSecondSpeed` 由 Downloader 库产生。库内公式（`src/Downloader/Bandwidth.cs`，`v5.9.6` tag 与 `master` 内容一致）：

```csharp
public void CalculateSpeed(long receivedBytesCount)
{
    int elapsedTime = Environment.TickCount - _lastSecondCheckpoint + 1;
    receivedBytesCount = Interlocked.Add(ref _lastTransferredBytesCount, receivedBytesCount);
    double momentSpeed = receivedBytesCount * OneSecond / elapsedTime; // B/s   (OneSecond = 1000)
    if (elapsedTime > OneSecond) { Speed = momentSpeed; ... SecondCheckpoint(); }
```
- `BytesPerSecondSpeed = _bandwidth.Speed`，即 **B/s（bytes per second）**，每 >1 s 刷新一次（`src/Downloader/AbstractDownloadService.cs` → `RaiseProgressChangedEvents`）。
  https://raw.githubusercontent.com/bezzad/Downloader/v5.9.6/src/Downloader/Bandwidth.cs
  https://raw.githubusercontent.com/bezzad/Downloader/master/src/Downloader/AbstractDownloadService.cs

v2rayN 的换算与展示值：
```csharp
var speed = (maxSpeed / 1000 / 1000).ToString("#0.0");
```
- `maxSpeed` 是 v2rayN 自己维护的**历次进度事件观测到的最大值**（不是库的 AverageSpeed）。
- 除以 `1000 * 1000` = 1e6，即 **十进制 MB/s**（不是 MiB/s，不是 bit/s，不是 KB/s），保留 1 位小数。
- 因此 v2rayN 界面/库中保存的测速值单位是 **MB/s（十进制）**，且是**观测峰值**而非平均值。
- 完成时再回调一次最终值；无任何正值时：有异常则回调 `value.Error.Message`，否则回调 `"0"`。
- 该字符串在 `SpeedtestService.DoSpeedTest` 里被 `decimal.TryParse` 后 `ProfileExManager.Instance.SetTestSpeed(...)` 存入 `ProfileExItem.Speed`（`decimal`），同时 `SetTestMessage(indexId, speed)` 存展示文本。
  https://raw.githubusercontent.com/2dust/v2rayN/master/v2rayN/ServiceLib/Manager/ProfileExManager.cs
  > 注意：错误消息（如超时/异常文本）也会走同一条 `UpdateFunc` 通道，但 `decimal.TryParse` 失败 → 不写入 Speed。

### 1.4 缓冲区 / HttpCompletionOption / Content-Length（库侧，版本 5.9.6）

v2rayN 引用 `Downloader` **5.9.6**（`v2rayN/Directory.Packages.props`）。以下行为读自 bezzad/Downloader；`Bandwidth.cs` 已比对 v5.9.6 tag 与 master 完全一致，其余库文件仅读 master，**未逐版校验 → 对 5.9.6 的确切行为属「未确证（仅 master 一致推定）」**。

- 读取块大小：`ChunkDownloader.ReadStream` 中 `byte[] buffer = ArrayPool<byte>.Shared.Rent(_configuration.BufferBlockSize);`；`DownloadConfiguration.BufferBlockSize` 默认 **1024**（getter 为 `Math.Min(MaximumSpeedPerChunk, field)`，setter 上限 1 MB）。测速场景 v2rayN 未改该值。
  https://raw.githubusercontent.com/bezzad/Downloader/master/src/Downloader/ChunkDownloader.cs
  https://raw.githubusercontent.com/bezzad/Downloader/master/src/Downloader/DownloadConfiguration.cs
- 默认分块/并发：`ChunkCount = 1`、`ParallelDownload = false`、`MinimumSizeOfChunking = 512`；若服务器不支持 Range，则强制单块。测速因此基本是**单连接顺序读**。
- `HttpCompletionOption`：`SocketClient.SendRequestAsync` 用
  ```csharp
  HttpResponseMessage response = await Client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancelToken)
  ```
  https://raw.githubusercontent.com/bezzad/Downloader/master/src/Downloader/SocketClient.cs
- `Content-Length` 处理：下载前先做一次 header 探测 `Client.GetFileInfoAsync(...)` → `Package.TotalFileSize = fileInfo.FileSize`；大小优先取 `Content-Range` 的总量，其次 `Content-Length`；若响应带非 identity 的 `Content-Encoding` 则返回 `-1`（未知）。未知大小时按单连接读到 EOF，完成时 `if (Package.TotalFileSize <= 0) Package.TotalFileSize = Package.ReceivedBytesSize;`。
- 库的取消语义：`MaxTryAgainOnFailure = 2`，读超时 `BlockTimeout` 默认 5000 ms，`HttpClientTimeout` 默认 100000 ms。

---

## 二、UDP 测试

### 2.1 `UdpTestService.CreateFromTarget`

文件：`v2rayN/ServiceLib.UdpTest/UdpTestService.cs`
https://raw.githubusercontent.com/2dust/v2rayN/master/v2rayN/ServiceLib.UdpTest/UdpTestService.cs

```csharp
private const string DefaultUdpTestType = "ntp";
private static readonly IReadOnlyDictionary<string, Func<IUdpTest>> UdpTestFactories =
    new Dictionary<string, Func<IUdpTest>>(StringComparer.OrdinalIgnoreCase)
    {
        ["ntp"] = () => new NtpService(),
        ["dns"] = () => new DnsService(),
        ["stun"] = () => new StunService(),
        ["mcbe"] = () => new McBeService(),
    };

public static UdpTestService CreateFromTarget(string? udpTestTarget, out string targetServerHost)
{
    var parts = udpTestTarget?.Split(':', 2);
    var udpTestType = parts?.Length > 0 ? parts[0] : DefaultUdpTestType;
    var udpService = Create(udpTestType);
    targetServerHost = parts?.Length > 1 && !string.IsNullOrEmpty(parts[1])
        ? parts[1]
        : udpService._udpTest.GetDefaultTargetHost();
    return udpService;
}
```
- **out 参数实名是 `targetServerHost`**（不是任务描述里的 `udpTestUrl`）。
- 映射规则：`Split(':', 2)` 的第一段是类型键，忽略大小写（`OrdinalIgnoreCase`）；`ntp`/`dns`/`stun`/`mcbe` 分别对应 4 个 tester。**空或未知键一律回落到 `ntp`**（`Create()` 内 `TryGetValue` 失败 → `UdpTestFactories[DefaultUdpTestType]()`）。
- 第二段是主机（或 `host:port`，因为只 Split 2 段）；为空则用该 tester 的 `GetDefaultTargetHost()`。
- **端口不在 CreateFromTarget 解析**：`ParseHostAndPort(string targetServerHost)`（私有）在发送时解析，无端口/解析失败则用 `GetDefaultTargetPort()`；支持 `[::1]:port` 形式，IPv4/域名取**最后一个** `:`。

### 2.2 默认 udp 测试目标

`ConfigHandler.LoadConfig()`（第 147–149 行）：
```csharp
if (config.SpeedTestItem.UdpTestTarget.IsNullOrEmpty())
{
    config.SpeedTestItem.UdpTestTarget = Global.UdpTestTargets.First();
}
```
`Global.UdpTestTargets` 首项 = `"ntp:pool.ntp.org"` → 默认类型 `ntp`，默认主机 `pool.ntp.org`，端口 `123`。
（`SpeedTestItem.UdpTestTarget` 属性本身无初始值：`public string UdpTestTarget { get; set; }`，见 `Models/Configs/ConfigItems.cs`，`AppManager.cs` 中无相关默认。）
https://raw.githubusercontent.com/2dust/v2rayN/master/v2rayN/ServiceLib/Models/Configs/ConfigItems.cs
https://raw.githubusercontent.com/2dust/v2rayN/master/v2rayN/ServiceLib/Manager/AppManager.cs

各 tester 默认 host:port（读自 `Tester/*.cs`）：

| 键 | 默认 host | 默认端口 | 文件 |
|---|---|---|---|
| ntp | `pool.ntp.org` | 123 | `v2rayN/ServiceLib.UdpTest/Tester/NtpService.cs` |
| dns | `8.8.8.8`（Google Public DNS） | 53 | `.../Tester/DnsService.cs` |
| stun | `stun.voztovoice.org` | 3478 | `.../Tester/StunService.cs` |
| mcbe | `pms.mc-complex.com` | 19132 | `.../Tester/McBeService.cs` |

请求包/校验（逐字要点）：
- NTP：`new byte[48]`，`ntpReq[0] = 0x23; // LI=0, VN=4, Mode=3`；校验 `Length >= 48 && (resp[0] & 0x07) == 4`。
- DNS：写死 20 字节查询（ID `0x1234`，`www.google.com` A IN）；校验长度 ≥12、ID 匹配、QR=1、RCODE=0、ANSWER>0。
- STUN：写死 Binding Request（type `0x0001`、magic `0x2112A442`、固定 12 字节 transaction id）；校验长度 ≥20 且 messageType ∈ {`0x0101`,`0x0111`}。
- MCBE：`0x01` + alive time + magic + GUID；校验长度 ≥48、首字节 `0x1C`、`Skip(17).Take(16)` magic 匹配、UTF-8 字符串解析后 `stringParts[8]` ∈ {Survival, Creative, Adventure, Spectator}。
- 接口 `IUdpTest` 在 **`Tester/IUdpTest.cs`**（不是 `ServiceLib.UdpTest/IUdpTest.cs`）：`BuildUdpRequestPacket()`、`VerifyAndExtractUdpResponse(byte[])`、`GetDefaultTargetPort()`、`GetDefaultTargetHost()`。
  https://raw.githubusercontent.com/2dust/v2rayN/master/v2rayN/ServiceLib.UdpTest/Tester/IUdpTest.cs

### 2.3 RTT 如何测量 / 失败行为

`UdpTestService.SendUdpRequestAsync(string targetServerHost, int socks5Port, CancellationToken ct = default)`：

```csharp
using var timeoutCts = new CancellationTokenSource(TimeSpan.FromSeconds(5));
using var linkedCts = CancellationTokenSource.CreateLinkedTokenSource(ct, timeoutCts.Token);
...
var roundTripTime = TimeSpan.MaxValue;             // Get minimum round trip time from two attempts
for (var attempt = 0; attempt < 2; attempt++)
{
    try
    {
        var stopwatch = new Stopwatch();
        stopwatch.Start();
        await channel.SendAsync(targetHost, targetPort, udpRequestPacket, linkedCt).ConfigureAwait(false);
        var (_, receiveResult) = await channel.ReceiveAsync(linkedCt).ConfigureAwait(false);
        stopwatch.Stop();

        if (!_udpTest.VerifyAndExtractUdpResponse(receiveResult)) { continue; }
        validUdpReceiveResult = receiveResult;
        var currentRoundTripTime = stopwatch.Elapsed;
        if (currentRoundTripTime < roundTripTime) { roundTripTime = currentRoundTripTime; }
    }
    catch (OperationCanceledException) when (timeoutCts.IsCancellationRequested) { throw; }
    catch { if (attempt == 1 && roundTripTime == TimeSpan.MaxValue) { throw; } }
}
if (validUdpReceiveResult != null) { return roundTripTime; }
throw new Exception("Failed to verify and extract UDP response.");
```
- 计时用 `System.Diagnostics.Stopwatch`，**包住「发送 + 接收」两步**（不含 `EstablishUdpAssociationAsync` 握手、不含 `BuildUdpRequestPacket`）。
- **最多 2 次尝试，取最小 RTT**；只有通过校验的响应才计入。
- 失败语义：校验不通过 → `continue`（静默重试）；非超时异常被 `catch { }` 吞掉，仅当「第 2 次尝试且仍无有效 RTT」时裸 `throw` 重抛原异常；总超时（5 s）→ `OperationCanceledException` 直接向上抛；循环结束仍无有效响应 → `throw new Exception("Failed to verify and extract UDP response.")`。
- 上层 `SpeedtestService.DoUdpTest`：`var responseTime = (int)(await udpService.SendUdpRequestAsync(udpTestUrl, it.Port, ct)).TotalMilliseconds;` → `SetTestDelay` + `UpdateFunc(it.IndexId, responseTime.ToString())`；抛出的异常在 `RunUdpTestAsync` 的 `catch (Exception ex) { Logging.SaveLog(...) }` 中被记录，**延迟列不会更新**。

### 2.4 是否走本地 SOCKS5 / 端口怎么选

**是**，走本地入站的 **SOCKS5 UDP ASSOCIATE**。

`v2rayN/ServiceLib.UdpTest/UdpTestService.cs`：
```csharp
using var channel = new Socks5UdpChannel("127.0.0.1", socks5Port);
if (!await channel.EstablishUdpAssociationAsync(linkedCt).ConfigureAwait(false))
{
    throw new Exception("Failed to establish UDP association with SOCKS5 proxy.");
}
```
`v2rayN/ServiceLib.UdpTest/Socks5UdpChannel.cs`：
```csharp
_udpClient = new UdpClient(new IPEndPoint(IPAddress.Any, 0));
_tcpClient = new TcpClient();
await _tcpClient.ConnectAsync(socks5Host, socks5TcpPort, cancellationToken)...
byte[] handshakeRequest = [Socks5Version, 0x01, 0x00];       // VER=5, NMETHODS=1, NO-AUTH
... CMD 0x03 (SocksCmdUdpAssociate) + ATYP=IPv4 0.0.0.0:0 ...
_relayEndPoint = new IPEndPoint(proxyRelayIp, proxyRelaySocksAddr.Port);
```
- 本地 UDP socket 由 `new UdpClient(new IPEndPoint(IPAddress.Any, 0))` 让系统**随机选本地端口**（`IPAddress.Any, 0`）；对端是 SOCKS5 响应中返回的 **relay endpoint**。
- 报文封装：`RSV(2 bytes 0x0000) + FRAG(1 byte 0x00) + ATYP/ADDR/PORT + payload`；`FRAG != 0` 时抛 `NotSupportedException("SOCKS5 UDP fragmentation is not supported")`。
- TCP 控制连接上的 `socks5Port`（UDP 测试端口）来自 `it.Port`（`ServerTestItem`），由测速配置生成时分配：

`v2rayN/ServiceLib/Services/CoreConfig/V2ray/CoreConfigV2rayService.cs`（批量路径）：
```csharp
var initPort = AppManager.Instance.GetLocalPort(EInboundProtocol.speedtest);
...
//find unused port
var port = initPort;
for (var k = initPort; k < Global.MaxPort; k++)
{
    if (lstIpEndPoints?.FindIndex(_it => _it.Port == k) >= 0) { continue; }
    if (lstTcpConns?.FindIndex(_it => _it.LocalEndPoint.Port == k) >= 0) { continue; }
    port = k; initPort = port + 1; break;
}
it.Port = port;
...
Inbounds4Ray inbound = new() { listen = Global.Loopback, port = port,
    protocol = nameof(EInboundProtocol.mixed),
    settings = new Inboundsettings4Ray() { udp = true, auth = "noauth" } };
```
单节点重载（`v2rayN/ServiceLib/Handler/CoreConfigHandler.cs`）：
```csharp
var initPort = AppManager.Instance.GetLocalPort(EInboundProtocol.speedtest);
var port = Utils.GetFreePort(initPort + testItem.QueueNum);
testItem.Port = port;
```
`AppManager.GetLocalPort`：
```csharp
var localPort = _config.Inbound.FirstOrDefault(t => t.Protocol == nameof(EInboundProtocol.socks))?.LocalPort ?? 10808;
return localPort + (int)protocol;
```
- 结论：UDP 测试端口 = **本地测速入站端口**，从 `GetLocalPort(EInboundProtocol.speedtest)` 起向上扫描未被占用（排除活动 TCP 监听与已建立连接）的第一个端口；入站是 **`mixed`（SOCKS+HTTP，`udp = true`）监听 `127.0.0.1`**，因此测试确实隧穿本地代理入站，而不是直连。
- 「`EInboundProtocol.speedtest` 相对 socks(10808) 的具体枚举数值」**未确证**（未读取到该 enum 定义文件；`Models/Enums/EInboundProtocol.cs` 路径返回 404）。
