# 115音乐 TV

Android TV / 安卓盒子上的 115 网盘音乐播放器。使用 115 网页版会话 Cookie 直连内部接口，无中间服务器，Media3(ExoPlayer) 流式播放，支持无损 FLAC/WAV、拖动进度、内嵌歌词与封面。

> 适配的曲库结构：网盘里一个「音乐」文件夹，下面**每首歌一个以歌名命名的子文件夹**，内放同首歌的多种音质文件（MP3/FLAC/WAV/臻品母带等）。列表一行一首歌，默认播放最高音质，播放中可一键切换音质。

## 功能

### 曲库与浏览
- **全部音乐**：直接列出「音乐」文件夹下的所有歌曲（一行 = 一首歌 = 一个歌名文件夹），懒加载翻页
- **文件夹**：通用目录浏览（面包屑 + 逐级返回），歌曲文件夹内点歌同样整库连播
- **搜索**：关键词搜全盘音频（独立搜索端点）
- **设置**：扫码重登 / 更换音乐文件夹 / 退出登录 / 接口自检

### 播放
- **整库连播**：点一首歌开始，后续歌曲按列表顺序自动拉取追加（滚动备播缓冲，断网补歌失败自动重试；歌曲播完的瞬间自愈续播）
- 播放模式：顺序 / 列表循环 / 单曲循环 / 随机
- **同曲多音质**：默认最高音质，播放页「音质」按钮组内循环切换，切换保持播放位置
- **歌词**：读取音频文件内嵌歌词（ID3v2 USLT / FLAC Vorbis 注释），带时间轴的逐行高亮滚动；无时间轴纯文本平铺。按需 Range 读取文件头，不下载整首
- **封面**：内嵌封面提取，播放页左封面右歌词同屏，封面高斯模糊全屏背景
- 记住播放位置：杀进程重启后侧栏「继续播放」恢复队列
- 无损解码失败自动降级 MP3（老盒子兜底）；连续网络失败自动暂停并刷新登录态
- 遥控器：媒体键全支持；播放页焦点链专为 D-pad 设计（进度条左右键 ±10s）

### 可诊断性（无 adb 也能排障）
- **日志页**：最近 30 条操作/请求记录（接口耗时、errNo、页面加载、播放状态）
- **崩溃自捕获**：崩溃堆栈 + 最近日志写入本地，下次启动自动展示在日志页

### 引导
- 未登录 / 登录过期 → 自动弹出扫码页
- 已登录但未设置音乐文件夹 → 自动弹出文件夹选择页（支持跳过）

## 登录

**扫码（正式方式）**：电视上显示二维码 → 115 手机 App 扫码并确认。走 115 网页版同款 qrcodeapi（本地渲染二维码 + 轮询 + 换取 Cookie），随后自动完成 WAF 安全校验。

> Cookie 中的反爬哈希 Cookie 由 JS 挑战产出、绑定 User-Agent。App 全局统一浏览器 UA，扫码时 WebView 自动产出配套反爬 Cookie。会话过期（errNo 990001）自动走「隐藏 WebView 刷新挑战 Cookie → 失败则弹扫码重登」两级恢复。

## 构建

需要 JDK 17 与 Android SDK（compileSdk 36）。

```bash
# local.properties 指定 SDK 路径
sdk.dir=C\:\\path\\to\\Android\\Sdk

./gradlew assembleDebug     # 输出 app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

安装到电视：U盘侧载 / `adb connect <TV-IP>:5555` 后 `adb install -r`。覆盖安装后桌面图标若显示旧图，重启盒子刷新启动器缓存。

### 自动发布

已配置 GitHub Actions（`.github/workflows/release.yml`）：推送 `v*` 标签即自动构建并把 APK 发布为 GitHub Release：

```bash
git tag v0.4.8
git push origin v0.4.8
```

到仓库 **Actions** 页可看构建进度，完成后 **Releases** 里就有 `115-music-v0.4.8.apk` 可直接下载安装。

## 技术要点（2026-10 实测）

- 列表：`GET webapi.115.com/files?aid=1&cid={目录}&o=user_ptime&asc=0&offset=&show_dir=1&limit=40&format=json`；搜索为独立端点 `/files/search`（同参数 + `search_value=`；`/files?search_value=` 会被服务端静默忽略）
- 播放直链：`GET webapi.115.com/files/download?pickcode={pc}` → `file_url`
- 直链支持 Range（可拖进度），必须携带 WAF 挑战 Cookie 否则 403 `no cookie`；直链有时效，403 后重取并原位续播
- 扫码：`qrcodeapi.115.com` 取 token → 二维码内容须为 `https://yun.115.com/scan/dg-<uid>` → 轮询状态 → 确认换 Cookie
- MediaController 只允许主线程访问；队列补歌统一在对账协程（主线程）执行，后台任务异常全局拦截只留痕

## 风险提示

- 使用 115 网页版内部接口，官方随时可能调整；App 已做目录 LRU 缓存、串行请求、开播才取直链并复用、预取下一曲，请勿二次高频调用
- 仅播放自己拥有合法使用权的文件；本项目仅供个人学习研究

## 工程结构

```
app/src/main/java/com/music115/tv/
├── App.kt / MainActivity.kt / ServiceLocator  # 组装 + 单 Activity 宿主
├── api/          # 115 Web 接口、诊断日志、崩溃捕获
├── auth/         # Cookie 仓库 / 扫码登录 / WAF 挑战刷新
├── net/          # 统一 Cookie + UA 注入
├── playback/     # Media3 服务 / 115 数据源(403自愈) / 队列对账管理 / 内嵌歌词提取
├── data/         # 列表缓存 / 音质分组 / LRC 解析 / 播放偏好
└── ui/           # 侧栏 + 5 个页面 + 播放页(模糊背景) + 适配器 + 焦点工具
```

## 致谢

- [p115client](https://github.com/ChenyangGao/p115client) —— 登录与文件接口行为的参考实现

---
当前版本 v0.4.8 (versionCode 39)
