# AnoTerm

[日本語](README.md) | [English](README.en.md) | **简体中文** | [한국어](README.ko.md)

Android 上的 SSH 客户端与终端模拟器。Kotlin + Jetpack Compose。

它存在的理由是**把全角字符按正确的宽度画出来**。Android 上的终端应用一旦遇到
中日韩文字，光标位置就会错位，显示随之崩坏。字符宽度的判定和 UTF-8 的解码都在
本项目内自行实现，原因就在这里。

> **界面语言目前只有英文和日文。** 本文档是中文的，应用本身还不是。
> 终端里显示的内容来自你连接的主机，不受此限制。

## 功能

### 作为终端

- **全角字符与组合字符按正确宽度渲染** — `CharWidth.kt` 解析 East Asian Width，
  `Utf8Decoder.kt` 处理代理对与不完整的字节序列
- **终端模拟** — `TerminalEmulator.kt`：备用屏幕、滚动区域、鼠标上报、DECCKM 等
- **在历史中查找** — 从 ⋮ 菜单进入。搜索的是这条连接上滚过的内容，
  既不是 shell 的历史，也不是文件的内容。命中处会被标色，用箭头在命中之间移动
- **带惯性的滚动** — 快速滑动会继续流动。回看历史时，底部会显示「回退了多少行」，
  按一下回到最新
- **改变画面高度时，正在读的那一行不会动** — 从顶部溢出的行会进入回滚缓冲而不是被丢弃，
  画面变高时再取回来。回看历史时，顶行完全不动

### 连接与认证

- **主机管理** — 主机、端口、用户名保存在 Room
- **认证** — 密码与私钥皆可，支持带密码短语的私钥
- **安装公钥（相当于 `ssh-copy-id`）** — 借助已经用密码连上的连接，把公钥追加到对端的
  `~/.ssh/authorized_keys`，只用 Android 就能切换到密钥认证。不会重复登记，也不会破坏已有的密钥
- **主机密钥校验（TOFU）** — 记录首次的密钥，**变了就拒绝连接**。首次连接时会显示记录下的
  指纹，可与服务器上的 `ssh-keygen -lf` 对照
- **凭据加密保存** — 使用 Android Keystore 的密钥加密的 `EncryptedFile`
- **应用锁** — 生物识别（可选）
- **后台连接** — 前台服务在息屏时也维持会话

### tmux

- **自动 attach** — 在主机设置里填入会话名，连接后会发送 `tmux new -A -s <名字>`，
  断线也不会丢掉正在做的事
- **窗口一览（≡）** — 从左侧拉出的抽屉里，列出所有连接的所有窗口，
  按「连接 → 会话 → 窗口」三层排列。**任何一个都能一次点到**。有新输出的窗口会被标记，
  里面在跑什么也会显示
- **切换栏** — 当前连接的 tmux 窗口横向排列，一点即到。可以从 ⋮ 菜单隐藏
- **完全不发送 prefix 键** — 所有 tmux 操作都改为执行 `tmux` 命令，因此无论前台应用是否
  占用 Ctrl-B、无论 `.tmux.conf` 把 prefix 改成什么，都照样有效
- 一览是通过另一条 SSH 会话读取的，不会打进终端，所以不会混进你正在看的画面

### 在手机上确认

- **打开图片** — 终端里出现的路径会带下划线，点一下就显示那张图。Claude Code 和 Codex
  写出图表或截图后只留下一个路径，在手机上原本到此为止。双指捏合可以放大
- **打开文档** — `.md`、`.kt`、`.json`、`.diff` 等五十余种，带行号且不折行
  （代码一折行就丢掉缩进，结构随之看不出来）
- **在终端旁边看页面** — 点开 `http://` 的 URL，终端旁边会打开一个浏览面。
  **流量走这条 SSH 连接**，所以只监听 `localhost` 的开发服务器不必对外暴露也能看；
  转发只在手机本机的 127.0.0.1 上监听。分割可以上下也可以左右，中间的分隔条决定占比。
  也可以交给手机的浏览器全屏查看
- 以上读取都走**与工作中的 shell 分开的另一条 SSH 会话**，不会把多余的输出混进命令行。
  相对路径会按 tmux 窗格的当前目录去找

### 为小屏幕而做的取舍

- **只留终端的全屏** — 上方的栏、tmux 那一行、状态栏都消失。双击进出，
  单击会在右上角短暂放下一个缩小按钮，返回操作也能退出
- **标签收进上方的栏里** — 标签名说的就是「现在在哪条连接」，和栏的标题重复，所以合成了一行
- **浮动应答板** — 收起键盘时，1/2/3、Esc 和键盘键浮在终端之上，
  这样为了回答一个确认提示就不必打开输入法、挡住正要确认的内容。长按中间的把手会换成
  方向键与回车（用于在列表里移动）。可以拖到顺手的位置
- **辅助键只在打字时出现** — Esc、方向键、Ctrl、Shift+Tab 随键盘一起出现和消失。
  屏幕小到不足以在阅读时还摆着它们
- **避开内屏摄像头** — 折叠屏展开后摄像头位于屏幕之内，留白会跟着系统上报的遮挡区域走。
  不够的话还可以在设置里加左侧留白
- **光标去到你触摸的地方** — 只要对端要求了鼠标上报（Claude Code、vim、开了 mouse 的 tmux）

### 其余

- **手势** — 下四分之一点击唤出键盘，滑动滚动（快速滑动会继续流动），双击进出全屏，
  长按拖动选择并复制，捏合改变字号。带下划线的路径和 URL 点击即可打开。全部写在帮助里
- **命令历史**、**便签**、**自定义快捷键**
- **显示选项** — 字号（捏合改变后会保留）、行距、配色

## 界面语言

英文与日文。默认跟随系统设置，可在设置内切换，无需重启应用。

所有文案都在 `strings.xml` 里，Kotlin 中不留任何字符串。`tools/check-strings.py` 在 CI 中运行，
会拒绝重名的键、只存在于一种语言的键、两边格式参数不一致的键，以及无人引用的键。

终端里显示的内容由连接的对端决定，不受此设置影响。

## 运行条件

- Android 7.0 (API 24) 以上
- 内置 JetBrains Mono，因此不依赖设备的字体设置

## 安装

### 安装 APK（推荐）

从 [Releases](https://github.com/mirute02/wanoterm/releases) 用 Android 设备的浏览器下载，
在通知栏或文件管理器里点击安装。不需要 PC，也不需要 adb。

首次会被要求允许「安装未知来源的应用」。Android 8.0 起该权限按应用划分，
对浏览器或文件管理器允许一次即可。

因为不经由 Play 商店，Play Protect 可能会弹出确认画面。构建为自签名，证书记录如下
（比起承诺「每个版本都会附上」，放在一处不变更容易核对）：

```
CN=AnoTerm, OU=AnoTerm, O=AnoTerm, C=JP
SHA-256: 7e1677c2e1094ca36b9584990beabb2fb1668b327417ead3b0993ec1b99bbbdb
```

用 `apksigner verify --print-certs <apk>` 与手上的 APK 对照。
**这个指纹不会变。** 换了密钥的应用会被 Android 当作另一个应用，无法覆盖更新，
因此指纹不同的 APK 不是本项目的产物。

### 自己构建

```bash
git clone https://github.com/mirute02/wanoterm.git
cd wanoterm
./gradlew assembleDebug
```

产物在 `app/build/outputs/apk/debug/app-debug.apk`。
没有签名密钥的环境里，release 构建会是未签名的，不能用于分发。

## 构建

```bash
./gradlew assembleDebug
```

`namespace` 与 `applicationId` 为 `app.anoterm`（debug 构建带 `.debug` 后缀）。

### 测试

```bash
./gradlew test
```

124 个单元测试，每次推送都在 CI 中运行。覆盖终端模拟、字符宽度计算、UTF-8 解码、
输入法未确定文本、改变尺寸时不丢行、从画面中切出路径与 URL（跨折行的拼接、shell 引用）、
tmux 输出的解析，以及转发目标的构造。

开发过程中并没有一直连着设备，所以**凡是必须用眼睛看的部分都没有被测试覆盖**。
能抽成纯逻辑的都抽出来测；绘制与输入的手感靠把 release 装进真机来确认。

### Release 构建

签名密钥不放进仓库，写在 `gradle.properties`（或 CI 的 Secrets）里：

```properties
WANOTERM_STORE_FILE=/absolute/path/to/release.jks
WANOTERM_STORE_PASSWORD=...
WANOTERM_KEY_ALIAS=...
WANOTERM_KEY_PASSWORD=...
```

证书的 DN 任何拿到 APK 的人都能读到，所以写项目名而不是真实姓名。
一旦分发过的密钥就不能再更换。详见 [docs/RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md)。

## 安全

SSH 客户端保管着对端的凭据，并且成为一个能用那把钥匙执行任意命令的终端。
[docs/security.md](docs/security.md) 写明了凭据是加密保存的、主机密钥变更时
**是拒绝连接而不是警告后继续**，以及一旦设备锁屏被攻破凭据仍可能被取出这一界限。

隐私政策见 [docs/PRIVACY_POLICY.md](docs/PRIVACY_POLICY.md)。

## 更新记录

[CHANGELOG.md](CHANGELOG.md)（日文）按版本记录了改动。APK 在
[Releases](https://github.com/mirute02/wanoterm/releases)。

## 许可

MIT — 见 [LICENSE](LICENSE)。依赖的许可证（从各自的构件中读取，而非从 README）
记在 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。

SSH 是 SSH Communications Security 的注册商标。本项目是独立的客户端，与该公司无关。
