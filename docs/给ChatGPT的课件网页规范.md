# 课件 HTML 规范（把这份直接发给 ChatGPT）

> 用途：让 ChatGPT 生成的单文件教学网页，能在「AI+自学」这个 Android 壳里
> 正常打开、不白屏、不跳页、能发音、能内嵌 B 站视频。

---

## 0. 先告诉它运行环境

```
运行环境：Android WebView（Chromium 148），横屏平板，屏幕 2176×1600。
页面是本地文件，通过 http://127.0.0.1:9090/view/xxx.html 打开，
没有地址栏、没有外网访问权限（只有 B 站域名放行）。
用户是初中生，用手指点，没有鼠标、没有键盘。
```

---

## 1. 硬性要求（违反就直接坏掉）

1. **必须是单个 .html 文件**：`<style>` 和 `<script>` 全部内联。
   - ❌ 不引用任何 CDN：MathJax、KaTeX、Tailwind、Bootstrap、Google Fonts、jsDelivr、unpkg 全都加载不出来
   - ✅ 公式用 HTML + Unicode + 内联 SVG + 纯 CSS 排版
2. **图片**用 `data:image/...;base64,...` 内联，或放**同目录**下的相对路径。
3. **不允许任何外链跳转**：`target="_blank"`、`window.open`、`<a href="https://...">` 一律不要。
   除了 B 站播放器 iframe，所有外部请求都会被 App 拦截。
4. **不要发 fetch / XMLHttpRequest 到外部**（本地静态页没有后端）。
5. **不要用 Service Worker、不要用 `type="module"`**，普通 `<script>` + ES2017 语法即可。
6. **文件名不要带空格和特殊符号**（`#`、`?`、`%`），中文没问题。

---

## 2. 视口与排版

```html
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="color-scheme" content="light">
```

- 按 **16:10 横屏**设计；正文容器 `max-width: 1400px; margin: 0 auto;`
- 字号：正文 ≥ 18px，题目 ≥ 20px，标题 28–40px，行高 1.6
- 字体用系统字体栈，别用 @font-face 外链：
  `font-family: -apple-system, "Noto Sans SC", "Source Han Sans SC", sans-serif;`
- **触控目标 ≥ 44×44px**，按钮之间 ≥ 8px 间距
- 页面背景色写死（`background:#fff; color:#1a2433;`），别依赖系统深色模式
- 用 `width:100%` 而不是 `100vw`（`100vw` 会撑出横向滚动条）

---

## 3. 点击「显示答案」绝对不能跳页（重点）

这是最常见的毛病：点一下答案，页面"抖一下"然后跑到别处去了。原因是平滑滚动 +
按钮抢焦点 + 锚点跳转。规则：

**❌ 禁止**
```css
html { scroll-behavior: smooth; }      /* 禁止 */
```
- ❌ 不要用 `<a href="#答案id">` 当按钮
- ❌ 不要用 `<details>/<summary>` 做答案（点开会自动滚动到 summary）
- ❌ 不要调用 `scrollIntoView()`、不要改 `location.hash`
- ❌ 不要用 `height / max-height / top` 做展开动画

**✅ 照这个写**

```css
html { scroll-behavior: auto; }                     /* 关掉平滑滚动 */
button { -webkit-tap-highlight-color: transparent; touch-action: manipulation; }
button:focus { outline: none; }
.answer { display: none; }
.answer.on { display: block; }                      /* 或直接用 hidden 属性 */
```

```js
function toggleAnswer(btn, id) {
  const el = document.getElementById(id);
  const y = window.scrollY;            // 展开前记住滚动位置
  const show = el.hidden;
  el.hidden = !show;
  btn.textContent = show ? '隐藏答案' : '显示答案';
  btn.setAttribute('aria-expanded', String(show));
  btn.blur();                          // 关键：别让按钮保持焦点
  if (Math.abs(window.scrollY - y) > 1) window.scrollTo(0, y);  // 兜底还原
}
```

```html
<!-- onmousedown 里 preventDefault：按钮点击后不抢焦点，从源头上避免被"滚动到可见区域" -->
<button type="button" onmousedown="event.preventDefault()"
        onclick="toggleAnswer(this,'ans-1')" aria-expanded="false">显示答案</button>
<div class="answer" id="ans-1" hidden>……答案内容……</div>
```

如果需要展开动画，只对 `opacity` / `transform` 做过渡，不要动高度。

---

## 4. 英语发音

App 已经内置了 `speechSynthesis` 兼容层，直接调用即可：

```js
function speak(text, rate = 0.9) {
  if (!('speechSynthesis' in window)) return;
  speechSynthesis.cancel();                 // 先停掉上一句，避免叠着读
  const u = new SpeechSynthesisUtterance(text);
  u.lang = 'en-US';
  u.rate = rate;
  u.pitch = 1;
  speechSynthesis.speak(u);
}
```

**❌ 这几种写法必须避免**（本 App 里会被误判为"不支持发音"）：

- ❌ `if (speechSynthesis.getVoices().length === 0) 禁用按钮`
  —— 本 App 的 `getVoices()` 返回空数组，但发音是好的（走系统 TTS）
- ❌ 弹「请在 Safari / Chrome 中打开」
- ❌ 用 `u.voice = speechSynthesis.getVoices()[0]` 指定音色

✅ 停止：`speechSynthesis.cancel()`

---

## 5. 内嵌 B 站视频

只用官方播放器 iframe：

```html
<div class="video-wrap">
  <iframe
    src="https://player.bilibili.com/player.html?bvid=BV1xx411c7mD&page=1&autoplay=0&danmaku=0&high_quality=1"
    scrolling="no" frameborder="0" allowfullscreen
    style="width:100%;aspect-ratio:16/9;border:0;border-radius:12px;background:#000"></iframe>
</div>
```

- `<div class="video-wrap">` 宽度至少 480px，否则 B 站播放器按钮会挤成一团
- 倍速（0.5x–2.0x）由 App 侧修复，**不需要**为了倍速而把播放器拉宽；
  但仍建议整行 16:9，不要塞进侧边栏/半栏
- 只用 `bvid=`（或 `aid=`），**不要用 b23.tv 短链**、不要用 `<a>` 跳转
- ❌ 不要引入 B 站官方 JS SDK / 播放器脚本（外部脚本被拦，会白屏）
- ❌ 不要自己写 `<video src="https://...bilivideo.com/...">`（有防盗链，直接 403）
- 需要登录才能看的视频：让用户在 App 首页点一次「B 站登录」扫码，Cookie 会一直保留

---

## 6. 其它媒体

- 本地视频：`<video src="同目录.mp4" controls playsinline preload="metadata"></video>`
- 浏览器/WebView 会拦截自动播放，**必须由用户点击后**再 `video.play()`
- 音频同理

---

## 7. 性能

- 单个 HTML 建议 **≤ 8MB**（现在 3–6MB 的打开正常）
- 图片先压到宽度 ≤ 1600px、JPEG 质量 80，再 base64
- 题目很多时**分页或折叠分组**，不要一次渲染几百个节点
- 动画用 `requestAnimationFrame` + `transform`，不要用 `setInterval` 改布局属性

---

## 8. 明确不要做的

| 不要 | 原因 |
|---|---|
| `alert()` / `confirm()` / `prompt()` 当主要交互 | 会阻塞页面，体验很差 |
| `document.write()` | 会覆盖整页 |
| `window.location.href = ...` | 会被 App 拦截，页面卡住 |
| 全局 `preventDefault` 掉 `touchstart` | 页面滚不动了 |
| `body { overflow: hidden }` | 长页面看不全 |
| `<iframe>` 嵌非 B 站的外站 | 被拦截，留一块空白 |
| 外部字体 / 图标库（Font Awesome 等） | 加载失败，图标全变方块 |

---

## 9. 生成后自检清单（让它自己核对一遍再交付）

- [ ] 只有一个 `.html` 文件，断网双击也能打开
- [ ] 全文搜不到 `http://` / `https://` 的外部资源（B 站播放器 iframe 除外）
- [ ] 点「显示答案」页面**不滚动、不抖动**，答案在按钮正下方展开
- [ ] 所有按钮高度 ≥ 44px，手指好点
- [ ] 英语发音按钮：点一次读一次，再点一次停
- [ ] 横屏 2176×1600 下没有横向滚动条
- [ ] 视频区域是 16:9，宽度够，能点开播放
- [ ] 没有 `alert` / `window.open` / `target="_blank"`

---

## 10. 一句话版（想省事就直接粘这段）

```
帮我生成一个单文件教学网页，要求：
1) 所有 CSS/JS 内联，不引用任何外部 CDN、字体、图片（图片用 base64 内联）；
2) 不用 target=_blank / window.open / alert，除 B 站播放器 iframe 外不出现任何外链；
3) html 不要设 scroll-behavior:smooth，不用 scrollIntoView / location.hash / details，
   点「显示答案」时页面不能滚动或跳动，按钮点击后 blur，答案在按钮正下方展开；
4) 英语发音直接调 speechSynthesis（lang='en-US'），不要检查 getVoices().length，
   不要提示"请在浏览器中打开"；
5) B 站视频用 <iframe src="https://player.bilibili.com/player.html?bvid=xxx&page=1">，
   16:9，宽度不小于 480px；
6) 按横屏 2176×1600 设计，正文 ≥18px，按钮 ≥44px，用系统字体，背景色写死。
```