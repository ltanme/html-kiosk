# 内嵌 B 站视频「倍速」失效的排查与修复

> 现象一句话：课件里嵌的 B 站视频能播，但倍速菜单点了没反应，或者弹一下就消失，
> 选不了 1.25x / 1.5x / 2.0x。电脑上用 ego lite 打开同一个课件却是正常的。

- 影响版本：App 全版本（只要用 `player.bilibili.com/player.html` 内嵌就会中招）
- 修复位置：`src/com/local/htmlkiosk/MainActivity.java` 的 `shouldInterceptRequest()`
- 验证环境：Android WebView（Chromium 148），2176×1600 @dpr2（CSS 视口 1088×800），
  课件里的 iframe 实测 676×379

---

## 1. 结论先讲

B 站给「站外未知网页」下发的播放器 HTML 里，嵌入模式是**服务端写死**的：

```js
const getChannelKind = function () {
    return nano.ChannelKind.Embedded_Other;   // ← 站外受限嵌入，写死在这里
};
```

于是 `configStore.channelKind = Embedded_Other` → `embeddedEnv = 3`，而播放器的控制栏是这么绑事件的：

```js
// npd.165.*.js（倍速控件）
e.prototype.addEventListener = function () {
    3 === this.configStore.embeddedEnv
        ? this.templateNodes.container.addEventListener("click", this.onBtnClickHandler)  // ← 受限模式
        : this.templateNodes.menu.addEventListener("click", this.onClickHandler);         // ← 正常模式
};

// 受限模式点下去干的事：跳「去 B 站看」+ 暂停，根本不改速度
e.prototype.onBtnClickHandler = function () {
    var t = this.portStore.getRedirectTime();
    (0, p.oh)("play", this.shareStore.embededUrl, this.configStore.embeddedEnv, this.configStore.bvid, t);
    this.portStore.pause();
};

// 正常模式才是真正设置倍速的
e.prototype.onClickHandler = function (t) {
    var e = t.target;
    if ("LI" === e.tagName.toUpperCase()) {
        var i = +e.dataset.value;
        this.templateNodes.container.classList.remove(this.showToken);
        i !== this.settingStore.getPlaybackRate() && this.settingStore.setPlaybackRate(i);
    }
};
```

所以「倍速」这一整块控件退化成了一个「进入哔哩哔哩，观看更高清」按钮：
点一下 = 暂停 + 尝试跳转（跳转被 App 的外链白名单拦掉，看起来就是「闪一下、没了」）。

**不是宽度问题，也不是 UA / Referer / 登录态问题**，纯前端改不了——下面第 3 节有实验记录。

修复分两层：

1. **改嵌入模式**：App 在 `shouldInterceptRequest()` 里自己取一次播放器 HTML，
   把 `ChannelKind.Embedded_Other` 换成 `ChannelKind.Embedded` 再交给 WebView；
2. **补触摸交互**：B 站桌面播放器靠**鼠标悬停**展开倍速菜单，手指点第二次弹不出来，
   所以再往 HTML 里注入一小段脚本，点按钮就展开并保持。

---

## 2. 怎么定位到的（可复现的排查路径）

### 2.1 先怀疑「控件被隐藏」

最早怀疑是播放器太窄，B 站会把右侧按钮组 `display:none`。用 Chrome DevTools 协议
（WebView 远程调试）量了一下，**控件其实是在的**：

```bash
adb forward tcp:9222 localabstract:webview_devtools_remote_$(adb shell pidof com.local.htmlkiosk | tr -d '\r')
curl -s http://127.0.0.1:9222/json | python3 -m json.tool | head
```

在播放器 iframe 里执行：

```js
getComputedStyle(document.querySelector('.bpx-player-ctrl-playbackrate')).display   // "block"
document.querySelector('.bpx-player-ctrl-playbackrate').getBoundingClientRect()     // 44×32，位置正常
```

### 2.2 再怀疑「点了没生效」

用 CDP 的 `Input.dispatchMouseEvent` / `dispatchTouchEvent` 发**真实（isTrusted）事件**，
再用 `elementFromPoint` 确认点击确实落在菜单项 `<li>` 上，同时监听事件：

```js
['pointerdown','mousedown','touchstart','click'].forEach(t =>
  document.addEventListener(t, e => log.push(t + ':' + e.isTrusted + ':' + e.target.className), true));
```

结果：`click:true:bpx-player-ctrl-playbackrate-menu-item` —— 事件打到了，但：

- `video.playbackRate` 没有变化；
- 高亮项还是 1.0x；
- `Runtime.exceptionThrown` 没有任何异常；
- 给 `HTMLMediaElement.prototype.playbackRate` 打 setter 探针，`__rateSets` 一直是空数组。

**事件到了、但 B 站的处理函数没跑** —— 说明点击根本进的是另一条分支。

### 2.3 拉源码看分支

用 `Debugger.getScriptSource` 把播放器脚本（`npd.165.*.js`、`core.*.js`）存到本地检索，
就找到了第 1 节那两段代码：`embeddedEnv === 3` 时绑的是 `onBtnClickHandler`。

再顺着 `embeddedEnv` 往上找：

```js
Object.defineProperty(t.prototype, "embeddedEnv", {
    get: function () {
        switch (this.channelKind) {
            case f.u5.Embedded:        return 1;
            case f.u5.Embedded_White:  return 2;
            case f.u5.Embedded_Other:  return 3;
            default:                   return 0;
        }
    }
});
```

`channelKind` 来自 `player.html` 的内联引导脚本，而那里是**写死**的：

```js
const getChannelKind = function () { return nano.ChannelKind.Embedded_Other; };
```

### 2.4 确认不是环境问题（这些都不是原因）

| 试验 | 结果 |
|---|---|
| 用 4 种 Referer（无 / `127.0.0.1:9090` / `bilibili.com` / B 站视频页）请求 `player.html` | 全部返回 `Embedded_Other` |
| 带上设备登录态 Cookie 再请求 | 仍然 `Embedded_Other` |
| 加随机参数绕 CDN 缓存（`&_=<时间戳>`） | 仍然 `Embedded_Other` |
| 量 iframe 宽度（676px，远大于按钮被折叠的阈值） | 按钮 `display:block`，排除宽度 |
| 换移动版 `html5mobileplayer.html` | 它的「倍速」点是**打开 B 站 App**，更差 |

即：B 站对**所有**站外未知网页都下发受限播放器，前端没有任何参数能改。

### 2.5 先验证修复思路，再改代码

不改 App，直接用 CDP 的 `Fetch` 域把响应体改掉，验证「改这一个枚举就能好」：

```python
Fetch.enable(patterns=[{"urlPattern": "*player.bilibili.com/player.html*", "requestStage": "Response"}])
# 命中后
body = Fetch.getResponseBody(requestId)          # 取原始 HTML（CDP 已解压）
new  = body.replace("ChannelKind.Embedded_Other", "ChannelKind.Embedded")
Fetch.fulfillRequest(requestId, responseCode=200, responseHeaders=headers, body=base64(new))
```

改写后播放器里「进入哔哩哔哩，观看更高清」按钮消失，点 1.5x：

```
RESULT: {"rate": 1.5, "active": "1.5x", "btn": "1.5x"}
```

思路成立，才开始改 Java。

---

## 3. 最终实现

### 3.1 拦截并改写播放器 HTML

```java
// MainActivity.java
private static final String BILI_EMBED_HACK_FROM = "ChannelKind.Embedded_Other";
private static final String BILI_EMBED_HACK_TO   = "ChannelKind.Embedded";

@Override
public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
    final String u = request != null ? request.getUrl().toString() : "";
    if (u.contains("player.bilibili.com/player.html")) {
        return patchedBiliPlayer(u);   // 失败返回 null，WebView 照常联网加载
    }
    return null;
}

private WebResourceResponse patchedBiliPlayer(String url) {
    // 逐 URL 缓存（LinkedHashMap，最多 24 条），避免每个 iframe 都重新拉一遍
    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
    conn.setConnectTimeout(8000);
    conn.setReadTimeout(15000);
    conn.setInstanceFollowRedirects(true);
    conn.setRequestProperty("User-Agent", desktopUa);                 // 桌面 Chrome UA
    conn.setRequestProperty("Referer", "https://www.bilibili.com/");
    conn.setRequestProperty("Cookie", CookieManager.getInstance().getCookie(url));
    if (conn.getResponseCode() != 200) return null;

    String html = new String(readBytes(conn.getInputStream()), "UTF-8");
    String out  = html.replace(BILI_EMBED_HACK_FROM, BILI_EMBED_HACK_TO);
    out = out.replace("</body>", BILI_TOUCH_SCRIPT + "</body>");      // 见 3.2
    return new WebResourceResponse("text/html", "utf-8",
            new ByteArrayInputStream(out.getBytes("UTF-8")));
}
```

几个要点：

- **失败就放行**：断网、被墙、B 站改版找不到那句话，都 `return null`，
  WebView 自己加载原版——最多退回「不能倍速」，不会把视频播挂；
- **带上 Cookie**：播放器 HTML 会按登录态出不同版本，别让用户掉登录；
- **不要转发 `Content-Encoding`**：Android 的 `HttpURLConnection` 默认会请求并透明解压 gzip，
  我们自己返回 `text/html; charset=utf-8` 的明文即可，否则 WebView 会按 gzip 解析乱码；
- **缓存**：同一课件的多个视频各cache一条，`LinkedHashMap(accessOrder=true)` 限制 24 条。

### 3.2 触摸下的「菜单点第二次就没了」

改完嵌入模式后，真机上出现新问题：**第一次点倍速能弹出菜单，选完 2.0x 想再改回 1.5x，
再点按钮菜单就不弹了**。

原因还是桌面播放器的设计：菜单是 `mouseenter`（悬停）展开的，`mouseleave` 收起。
鼠标会反复进出，手指不会——第一次点还能蹭到一次浏览器合成的 `mouseenter`，
之后再点就既没有 enter 也没有 leave，菜单自然弹不出来。

所以注入一小段脚本，**直接伪造「悬停之后的状态」**（给按钮加 `bpx-state-show` 类），
比派发假 `mouseenter` 事件更可靠——假事件会被 B 站自己的定时器覆盖掉：

```js
(function () {
  var ST='bpx-state-show', SEL='.bpx-player-ctrl-playbackrate',
      ITEM='.bpx-player-ctrl-playbackrate-menu-item';
  var box=null, obs=null, until=0;
  function stop(){ if(obs){obs.disconnect();obs=null;} box=null; until=0; }
  function show(){
    if(!box) return;
    if(Date.now()>until){ stop(); return; }
    if(!box.classList.contains(ST)) box.classList.add(ST);
  }
  document.addEventListener('click', function (e) {
    var t=e.target; if(!t||!t.closest) return;
    if(t.closest(ITEM)){ stop(); return; }        // 选档位：交还 B 站
    var b=t.closest(SEL); if(!b){ stop(); return; } // 点别处：关掉
    box=b; until=Date.now()+20000; show();
    if(obs) obs.disconnect();
    obs=new MutationObserver(show);               // 被 B 站隐藏定时器摘掉就补回来
    obs.observe(box, {attributes:true, attributeFilter:['class']});
  }, true);
})();
```

效果：点倍速按钮 → 菜单展开并**保持 20 秒**，从容选档位；选定或点别处自动恢复原逻辑。

---

## 4. 踩过的坑

1. **`WebView.getSettings()` 不能在后台线程调**
   `shouldInterceptRequest()` 跑在 `ThreadPoolForeg` 上，直接读 `webView.getSettings()`
   会抛 `RuntimeException: A WebView method was called on thread 'ThreadPoolForeg'`。
   解法：初始化时把伪装好的 UA 存进字段 `desktopUa`，后台线程只用这个字符串。
   （现象是补丁静默失效，靠 `adb logcat | grep Kiosk` 才看出来。）

2. **坐标别用旧值**
   调试时页面会滚动，`getBoundingClientRect()` 要现量现用；否则点击会落到视频区域，
   表现为「点倍速把视频暂停了」，很容易误判成 B 站的 bug。

3. **合成事件也要当真事件测**
   `element.click()` 产生的 `isTrusted=false` 事件，和真实触摸不一定走同一条路径。
   最终验证用 `Input.dispatchTouchEvent`（真实触摸）+ 真机手点。

4. **别只看 `display`**
   按钮 `display:block`、事件也命中了，但行为依旧不对——
   说明问题在**处理函数绑到哪一条分支**，要顺着状态（`embeddedEnv`）往上查。

---

## 5. 验证方法

### 5.1 判断补丁是否生效（一行）

播放器受限模式会渲染「进入哔哩哔哩」按钮，正常模式没有：

```js
!!document.querySelector('[class*=relation-button]')   // false = 补丁生效
```

### 5.2 跑完整交互链路

```js
// 依次执行并观察 video.playbackRate / 按钮文案
点倍速按钮 → 菜单 display:block
点 2.0x    → playbackRate=2,  按钮显示 2.0x
再点倍速   → 菜单 display:block   ← 这一步以前是坏的
点 1.5x    → playbackRate=1.5, 按钮显示 1.5x
```

真机实测结果（触摸事件）：

| 步骤 | playbackRate | 菜单 |
|---|---|---|
| ① 点开菜单 | 1 | block |
| ② 选 2.0x | 2 | none |
| ③ 再点倍速 | 2 | **block**（修复前：none） |
| ④ 选 1.5x | 1.5 | none |

### 5.3 日志

```bash
adb logcat -d | grep Kiosk
# I Kiosk : bili player patched: 6721 bytes          ← 改写成功
# W Kiosk : bili player html changed (...), 只注入触摸脚本  ← B 站改版，但仍可用
# W Kiosk : bili player patch failed: ...            ← 网络等原因，已放行原版
```

---

## 6. 备选方案与取舍

| 方案 | 结论 |
|---|---|
| 把 iframe 拉宽 / 加 `as_wide` 等参数 | 无效，按钮本来就没被隐藏 |
| 改 Referer / UA / 带登录 Cookie | 无效，B 站对所有站外网页都下发受限播放器 |
| 改用 `html5mobileplayer.html` | 它的倍速按钮是「打开 B 站 App」，体验更差 |
| iframe 嵌正片页 `www.bilibili.com/video/...` | 没有 `X-Frame-Options` 可以嵌，但 676×379 里塞整站页面没法用 |
| App 自己解析 B 站视频流做播放器 | 工作量大，防盗链/签名一变就废 |
| 在 WebView 里给 iframe 注入 JS | 跨域，主文档 `evaluateJavascript` 到不了子框架 |
| **拦截 HTML 改写 + 注入触摸脚本（当前方案）** | 改动最小、不依赖 B 站接口，改版时自动降级 |

---

## 7. 如果以后又坏了

按顺序看三处：

1. `adb logcat | grep Kiosk` 有没有 `bili player patch failed`；
2. 播放器 HTML 里那句还在不在：
   `curl -s "https://player.bilibili.com/player.html?bvid=<任意>&_=$(date +%s)" | grep ChannelKind`
3. 倍速控件的类名是否还叫 `.bpx-player-ctrl-playbackrate`、展开类是否还叫 `bpx-state-show`
   （对应 `BILI_EMBED_HACK_*` 与 `BILI_TOUCH_SCRIPT` 两个常量，改这一处即可）。

即使这三处全变，App 也只会退回「不能倍速」，不会出现白屏或播放失败。