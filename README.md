# AI+自学 · 无地址栏 HTML 浏览器

一个极简 Android 壳：启动即横屏全屏 WebView，没有地址栏；内置 `9090` 管理端口，
电脑浏览器上传任意 HTML，设备书库自动刷新，点开即全屏原样渲染。

## 安装

```bash
adb install -r "AI+自学.apk"
```

- 包名：`com.local.htmlkiosk`
- minSdk 26 / targetSdk 34，横屏、沉浸式全屏、保持常亮

## 使用

1. 打开 App，首页会显示 `http://<设备IP>:9090`
2. 电脑或手机浏览器打开这个地址
3. 把 `.html` 拖进去，或点「选择文件 / 选择整个文件夹」上传
4. 设备上的书库自动刷新，点条目即全屏打开

上传目录：

```
/sdcard/Android/data/com.local.htmlkiosk/files/library/
```

## 也可以 adb push

```bash
adb push --sync ~/课件/ /sdcard/Android/data/com.local.htmlkiosk/files/library/
```

目录结构就是书库结构，例如 `化学/第四单元/互动补课.html`。

## 页面能力

- 任意 HTML/CSS/JS，不注入、不改 DOM，按原样渲染
- 同级相对资源（图片、CSS、JS、字体）可直接引用
- `localStorage`、`history`、`alert/confirm/prompt`、`<input type=file>` 正常
- 视频全屏、手势缩放、横向/纵向滚动
- **语音朗读（发音）**：WebView 本身没有 Web Speech API，App 内置了一个 `speechSynthesis`
  兼容层，把 `speak()/cancel()/getVoices()` 接到 Android 系统 TTS；课件里的「点击发音」
  直接可用。系统里没装语音引擎（或缺少英语语音包）时会弹提示
  - manifest 里的 `<queries><intent action=TTS_SERVICE/></queries>` 不能删：targetSdk 30+
    的包可见性限制会让 `TextToSpeech` 静默初始化失败（没声音也不报错）
- **B 站视频内置播放**：WebView 的 UA 伪装成 macOS 桌面版 Chrome。B 站 CDN 的分片
  （`*.bilivideo.com/*.m4s`）只认「桌面 UA + `bilibili.com` Referer」，安卓 UA 一律 403，
  播放器还会顺带引导去下载 B 站 App
- **内嵌播放器的「倍速」修复**：`player.bilibili.com/player.html` 给站外未知网页下发的
  是受限播放器——HTML 里这句是写死的：
  `const getChannelKind = function(){ return nano.ChannelKind.Embedded_Other; }`，
  于是 `embeddedEnv=3`，整个倍速控件退化成一个「进入哔哩哔哩」按钮：
  点下去只会暂停 + 尝试跳转，选不了 1.25x/1.5x/2.0x。
  跟 Referer、UA、登录态都无关，纯前端也改不了，所以在 App 的
  `shouldInterceptRequest` 里自己取一次播放器 HTML：把一个枚举换成
  `ChannelKind.Embedded`，再原样交给 WebView；取不到（断网/B 站改版）就直接放行，
  不影响播放
  - 顺带注入一段触摸脚本：B 站桌面播放器是「鼠标悬停在倍速按钮上就展开菜单」，
    手指第一下能触发一次合成的 mouseenter，第二下就没有了——「2.0x 想改回 1.5x」
    时菜单根本不弹。脚本让点按钮就展开并保持 20 秒，选档位/点别处再交还原逻辑
- **只放行 B 站外链**（bilibili.com / b23.tv / bilivideo.com / geetest.com 等），
  其它外部地址直接拦掉；已经处在 B 站页面时（扫码登录常弹安全验证、下载 App 之类）
  静默丢弃、不弹提示，避免打断登录流程
- 首页「B 站登录」按钮：在 App 内打开登录页，扫码/短信登录一次，Cookie 常驻；
  未登录时打开 B 站页面或点课件里的「网页内试播」，会先弹登录页，登录后自动回到原页面
- 首页「添加到桌面」：调用 `ShortcutManager.requestPinShortcut`，确认后图标由 Launcher3
  写入桌面数据库。注意 Launcher3 会把它放到**第二屏**；本机已用 root 直接改
  `/data/data/com.android.launcher3/databases/launcher_6_by_5.db` 的 `favorites` 表，
  把图标落在首屏 `(screen=0, cellX=4, cellY=1)`。手工改库必须先 `am force-stop
  com.android.launcher3`，否则改动会被桌面回写覆盖
- 左下角「‹ 书库」常驻淡显，单击回书库；打开新页面时会先显示「正在打开…」
- 关闭阅读器时直接销毁 WebView：不残留历史（避免返回退到 about:blank 白屏），
  同时把大页面的内存还给系统；渲染进程被系统回收时会自动退回书库而不是留白屏

## 构建

```bash
./build.sh
```

依赖本机 Android SDK（build-tools 36、platforms/android-34）与 JDK 8 + 17。

## 图标

用的是自适应图标（`res/mipmap-anydpi-v26/ic_launcher.xml`）：
前景是缩小到画布 72% 的照片 + 模糊铺满的同图打底，背景是整图模糊。
这样系统不管把图标切成圆形还是方圆形，看到的都是照片本身——
既不会有白边（旧做法是普通 PNG，桌面会给它加一圈白底），也不会把脸裁掉。

换图标时改这张源图，重新生成各密度再打包：

```bash
python3 tools/make_icon.py "新图标.png" res && ./build.sh
```

## 服务端接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/` `/upload` | 上传页 |
| GET | `/api/list` | 书库 JSON |
| POST | `/upload` | 上传，`X-Filename: urlencode(相对路径)` |
| POST | `/api/delete?path=` | 删除 |
| GET | `/view/<path>` | 原样返回文件，支持 Range |

## 已知限制

- 电脑和平板必须在**同一网段**才能用 `http://<设备IP>:9090` 上传；
  跨网段（例如电脑在 192.168.1.x、平板在 192.168.2.x）会被路由器挡掉，
  可用 `adb forward tcp:9090 tcp:9090` 后访问 `http://127.0.0.1:9090` 临时绕过
- 非 B 站的正文外链（如资源站）会被拦下，只提示「已阻止打开外部地址」
- 发音依赖系统的 TTS 引擎（如 Google 文字转语音）；精简 ROM 若没装，需要自行安装并
  在「设置 → 语言和输入法 → 文字转语音」里选好引擎与英语语音数据。
  测试机装的是 F-Droid 上的 Marmalade TTS（内置 Kitten TTS Nano，音色 Rosie，
  已设为 `tts_default_synth`），**全程离线**、不依赖 Google 服务
- 下载链接未启用（点击会提示）
- 摄像头/麦克风权限未实现
- `9090` 无鉴权，同一局域网内可上传/删除
- 上传服务跟随 App 进程；进程被杀后需重新打开 App