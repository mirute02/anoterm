# AnoTerm 安定性・電池消費 調査レポート (2026-07-07)

Opus / Sonnet への実装引き継ぎ用。調査対象コミット: `3ebbab1` (main)。
targetSdk = 36 / minSdk = 24 / sshj 0.40.0。

> **実装ステータス (2026-07-07 更新)**: 第 1 弾として P0 の全項目 + 安全な P1 のクイックウィン
> + S-01 を実装済み (§11 に詳細)。debug ビルド・ユニットテストは通過。**未実装は F-04 / F-12 /
> F-13 / F-14 / F-15 と S/U 系列** — いずれも大きめの機能追加か設計判断を伴うため次弾に回した (§11)。

## 0. ユーザーが観測している症状と原因のマッピング

| 症状 | 有力な原因 (確度順) |
|---|---|
| バックグラウンドで動き続けて電池を食う | F-04 (keepalive 30s 固定), F-03 (START_STICKY ゾンビ FGS), F-05 (接続キャンセル時の SSHClient リーク), F-07 (死んだセッションでも FGS が生存), F-09 (バックグラウンドでも受信パース・collect が継続), F-10 (SIGWINCH 連発による無駄トラフィック) |
| 放っておくと予期せぬ理由で落ちる | **F-01 (dataSync FGS の 6 時間タイムアウト → システムによる強制クラッシュ)**, F-12 (Doze で keepalive 失敗 → 切断。アプリ死ではなくセッション死), F-13 (多タブ時のメモリで LMK kill), F-02 (バックグラウンド中の FGS 起動クラッシュ) |
| 不安定な動き | F-08 (受信ループが Dispatchers.Default を占有 → スレッド枯渇), F-06 (タブクローズがメインスレッドでネットワーク I/O), F-11 (書き込みキュー溢れで入力バイト黙殺), F-05 (CancellationException を握り潰して誤った「接続失敗」表示), F-10 (IME アニメ中の resize/SIGWINCH 連発) |

**最初にやるべき実測**: 実機で `adb shell dumpsys activity exit-info app.anoterm` を実行し、
過去のプロセス終了理由 (CRASH / ANR / LOW_MEMORY / FGS timeout 等) を確認すること。
どの仮説が実際に起きているかを一発で切り分けられる (§4 参照)。

---

## 1. クラッシュ / プロセス死 (P0)

### F-01 【確度: 確定】dataSync FGS の 6 時間制限で system がアプリを殺す

- `AndroidManifest.xml:43` — `foregroundServiceType="dataSync"`
- `app/build.gradle.kts:14` — `targetSdk = 36`
- `SshForegroundService.kt` — `onTimeout()` を override していない

Android 15 (API 35) 以降、targetSdk 35+ のアプリの `dataSync` FGS は **24 時間あたり合計約 6 時間**しか動けない。上限に達すると `Service.onTimeout()` が呼ばれ、数秒以内に `stopSelf()` しないと **`RemoteServiceException$ForegroundServiceDidNotStopInTimeException` でアプリがクラッシュする**。デフォルトの `onTimeout()` は何もしないため、現状は SSH セッションを 6 時間放置すると必ずクラッシュする。「放っておくと落ちる」の最有力原因。

**修正方針**:
1. `foregroundServiceType` を `specialUse` に変更し、manifest の `<service>` に
   `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="ssh terminal session keep-alive" />` を追加。
   `FOREGROUND_SERVICE_SPECIAL_USE` permission も宣言。`specialUse` にはタイムアウトが無い
   (SSH クライアントは Google のガイドでも specialUse の想定ユースケース。Play 提出時に用途申告が必要)。
2. 保険として `onTimeout(startId, fgsType)` を override し、通知でユーザーへ告知 → `sessionManager.closeAll()` → `stopSelf()` する安全弁を実装 (将来の制限変更に備える)。

### F-02 【確度: 高】バックグラウンド中に接続が完了すると FGS 起動でクラッシュ

- `ui/terminal/TerminalScreen.kt:212-218` — `SshChannel.connect()` (最長 15 秒ブロック) の**後**に `sessionManager.getOrCreate()` → `SshForegroundService.start()`
- `SshForegroundService.kt:88-91` — `ContextCompat.startForegroundService()` を無防備に呼ぶ

接続中 (最長 15 秒) にユーザーがホーム画面へ戻る/画面を消すと、接続完了時点でアプリはバックグラウンド。Android 12+ ではバックグラウンドからの FGS 起動は `ForegroundServiceStartNotAllowedException` を投げる。どこにも catch がないため**即クラッシュ**。

**修正方針**:
1. FGS の起動タイミングを「接続開始時」(ユーザー操作直後 = 確実にフォアグラウンド) に前倒しし、接続失敗時に stop する。
2. さらに `SshForegroundService.start()` 内を `try/catch (ForegroundServiceStartNotAllowedException)` で包み、失敗したら pending フラグを立てて `MainActivity.onStart()` で再試行する fallback を入れる。

### F-03 【確度: 高】START_STICKY によるゾンビ FGS (通知が出続け、電池を食う)

- `SshForegroundService.kt:38` — `return START_STICKY`
- `SshForegroundService.kt:33-39` — `onStartCommand` は無条件に `startForeground`

プロセスがクラッシュ/LMK で死ぬと、システムは START_STICKY のサービスを null intent で再起動する。再起動後の新プロセスでは `SshSessionManager.bundles` は空 (セッションは全滅) だが、サービスは通知を出して**永久に生き続ける**。止まる契機は `closeTab`/`closeAll` だけで、セッションが無いので誰も呼ばない。F-01 のクラッシュ後にこれが発生すると「落ちたのに通知だけ残って電池を食う」ループになる。

**修正方針**: `START_NOT_STICKY` に変更。加えて `onStartCommand` で `AnotermApp.get().sessionManager.activeTabIds().isEmpty()` なら `stopSelf()`。

### F-13 【確度: 中】多タブ + 横長画面でヒープ肥大 → LMK kill

- `terminal/emulator/TerminalBuffer.kt:60` — `maxScrollback = 2000` (ハードコード)
- スクロールバック 1 行 = `Array<Cell>` (data class、約 36B/cell)。80 桁で約 5–6 MB/タブ、200 桁で約 14–16 MB/タブ
- タブごとに独立バッファ (`TerminalSessionController.kt:46`)。10 タブ × 横長で ~160 MB に達しうる

バックグラウンドで LMK に殺されると、ユーザーには「勝手に落ちて全タブ消えた」ように見える。

**修正方針 (段階的)**:
1. 短期: アプリ全体のスクロールバック総量バジェット (例: 32 MB) を設け、超過時は非表示タブから削る。
2. 中期: `Cell` のオブジェクト配列を、プリミティブ列 (`IntArray codePoint` + `IntArray styleId` + style intern テーブル) に置換。メモリ 1/4 以下 + GC 圧激減。
3. `onTrimMemory(TRIM_MEMORY_BACKGROUND)` で非表示タブの scrollback を積極的に落とす。

---

## 2. 電池消費 (P0–P1)

### F-04 【確度: 確定】keepalive 30 秒固定でモバイル無線が眠れない

- `ssh/SshChannel.kt:114` — `ssh.connection.keepAlive.keepAliveInterval = 30`

30 秒ごとに keepalive パケット送信 + サーバ応答受信 = **1 日 2,880 回の無線ウェイクアップ**。モバイル網では 1 パケットごとに無線が数秒アクティブ状態を維持するため、セッションを張りっぱなしにするだけで恒常的な電池消費源になる。「バックグラウンドで電池を食う」の定量的に最大の要因である可能性が高い。

**修正方針**:
1. keepalive 間隔を設定項目化 (デフォルト 120 秒程度、0 = 無効も選択可)。
2. tmux 統合ホストでは「バックグラウンド N 分で自前切断 → フォアグラウンド復帰時に自動再接続 + `tmux new -A` で再アタッチ」というポリシーを設定として提供する (tmux がセッション本体を守るので体験を損なわない)。ProcessLifecycleOwner でアプリ全体の前面/背面を監視すれば実装できる。

### F-05 【確度: 高】接続中に画面遷移すると SSHClient がリークして永久に keepalive を打ち続ける

- `ui/terminal/TerminalScreen.kt:171` — 接続処理が `LaunchedEffect(tabId, retryNonce)` 内
- `ssh/SshChannel.kt:74-136` — `connect()` は成功パスで cleanup を持たない (途中で throw / キャンセルされると `ssh` を誰も閉じない)
- `ui/terminal/TerminalScreen.kt:248` — `catch (t: Throwable)` が `CancellationException` も飲み込む

シナリオ: ユーザーがタブを開いて接続中に back/別タブへ移動 → LaunchedEffect がキャンセル → `withContext` 完了時に `CancellationException` → (a) `SshChannel` インスタンスが返らず、**接続済み・keepalive スレッド稼働中の `SSHClient` が浮遊** (閉じる手段なし、GC でも finalizer なし)。リークした接続は 30 秒ごとに通信し続け、電池と接続枠を食う。(b) `catch (Throwable)` がキャンセルを「接続失敗」として UI に出す誤動作も併発。

**修正方針**:
1. `SshChannel.connect()` 全体を `try { ... } catch (t: Throwable) { runCatching { ssh.disconnect() }; throw t }` で包む (キャンセル含む全失敗経路で `ssh` を閉じる)。sshj の `connect`/`authXxx` はブロッキングなのでキャンセル即中断はされないが、完了後に必ず後始末される。
2. `TerminalScreen` 側の catch は `if (t is CancellationException) throw t` を先頭に。
3. 接続そのものを LaunchedEffect ではなくアプリスコープ (SessionManager 内の scope) で行い、UI はその StateFlow を観測するだけにするのが本筋のリファクタ (画面遷移と接続ライフサイクルの分離)。

### F-06 【確度: 高】タブクローズがメインスレッドで SSH 切断パケットを送ろうとして失敗 → 接続リソースがリーク

- `ui/terminal/TerminalScreen.kt:400,579` / `ui/hosts/HostListScreen.kt:201` — UI イベントから直接 `closeTab()`
- `ssh/SshSessionManager.kt:108-113` — `closeTab` → `dispose()` を呼び出しスレッド (= メイン) で実行
- `ssh/SshSessionManager.kt:72-77` — `dispose()` → `channel.close()`
- `ssh/SshChannel.kt:56-63` — `close()` は `shell.close()` / `session.close()` / `ssh.disconnect()` と**ネットワーク書き込みを伴う**処理をその場で実行

メインスレッドなので `NetworkOnMainThreadException` になり、`runCatching` が握り潰す。結果、DISCONNECT パケットは送られず、sshj の Reader / KeepAlive スレッドや socket が生き残る可能性がある (タブを閉じたのに裏で接続が残る = 電池 + サーバ側セッション残留)。メモリ `feedback_android_socket_io` に記録済みの「socket I/O は必ず IO ディスパッチャー」の原則違反が close 経路にだけ残っている形。

**修正方針**: `SessionBundle.dispose()` 内で `channel.close()` を IO スコープに逃がす
(例: `writeScope` キャンセル前に `withContext(Dispatchers.IO)` で close する suspend 化、
または SessionManager にアプリスコープの IO scope を持たせ `scope.launch(Dispatchers.IO) { channel.close() }`)。

### F-07 【確度: 高】死んだセッションが FGS を延命し続ける / 通知が実態を反映しない

- `ssh/SshSessionManager.kt:100-106` — 死んだ bundle も意図的に保持 (tmux 保護のため。これ自体は正しい)
- ただし切断後も FGS は動き続け、通知は常に「AnoTerm SSH 実行中」(`SshForegroundService.kt:76`)
- 自動再接続は存在しない (`TerminalSessionController.kt:119-123` で `Disconnected` にするだけ)

Doze や電波断でセッションが全滅しても、FGS + プロセスは永久に残る。ユーザー視点では「何もしていないのにバックグラウンドで動き続ける」。

**修正方針**:
1. `connectionState` を SessionManager が集約監視し、「全セッションが Disconnected/Failed になったら X 分後に FGS を止める (通知で告知)」ポリシーを入れる。
2. 通知に「すべて切断」アクションボタンと、接続数/状態のテキストを追加 (`setContentText("2 セッション接続中")` 等)。
3. フォアグラウンド復帰時に dead session を検出したら再接続を促す (または自動再接続 + tmux 再アタッチ)。

### F-09 【確度: 中】バックグラウンドでも受信パースと collector が動き続ける

- `terminal/TerminalSessionController.kt:98-118` — 受信ループはライフサイクル非依存 (設計通りだが、`emulator.feed` の CPU はバックグラウンドでも消費)
- `terminal/compose/TerminalHost.kt:57-59` — `redrawSignal` の collect が素の `LaunchedEffect` (STOPPED でも稼働)
- `terminal/compose/TerminalHost.kt:62-73` + `ui/terminal/TerminalScreen.kt:435-439` — **bell が二重購読**。TerminalScreen 側はスロットル無しで、バックグラウンドのタブの BEL でも振動する

**修正方針**: redraw/bell の collect を `repeatOnLifecycle(STARTED)` でラップ。bell 購読は一本化 (TerminalScreen 側を削除) し 500ms スロットルに統一。受信ループ自体は tmux 方針 (F-04-2) とセットで検討。

### F-10 【確度: 中】再コンポーズのたびに resize / SIGWINCH が飛ぶ

- `terminal/compose/TerminalHost.kt:44-52` — `AndroidView.update` が毎回 `setFontSizeSp` 等を呼ぶ
- `terminal/view/TerminalView.kt:239-244` — `setFontSizeSp` は値が同じでも `reflowToViewport()` → `controller.resize()`
- `terminal/TerminalSessionController.kt:127-131` — `resize()` はサイズ不変でも無条件に PTY resize (SIGWINCH) + 再描画発火
- `ui/terminal/TerminalScreen.kt:445-447` — `WindowInsets.ime.getBottom()` が IME アニメーション中フレームごとに再コンポーズを誘発

IME 開閉のたびに SIGWINCH が連発 → リモートの tmux / Claude Code が全画面再描画 → 受信トラフィック増 → 電池と表示のちらつき。tmux scrollback 重複問題 (直近コミット 3ebbab1 の動機) にも寄与している可能性がある。

**修正方針**: (1) `controller.resize()` に「rows/cols が前回と同じなら no-op」ガード、(2) `TerminalView` の各 setter に差分適用ガード、(3) `update` ブロックで渡す値を `remember` で安定化。

---

## 3. 不安定動作 (P1)

### F-08 【確度: 高】受信ループが Dispatchers.Default をブロックしてスレッド枯渇

- `terminal/TerminalSessionController.kt:60` — `CoroutineScope(SupervisorJob() + Dispatchers.Default)`
- 同 `:98-103` — その scope で `input.read(buf)` (ブロッキング I/O) を回す

`Dispatchers.Default` のワーカー数は CPU コア数 (最低 2)。**タブ 1 つにつき 1 ワーカーを read で常時ブロック**するため、4 コア端末で 4 タブ開くと Default プールが完全に埋まり、Default を使う他のコルーチン (Compose の一部処理や他タブの処理) が動けなくなる → フリーズ/ANR 級の不安定動作。ブロッキング I/O は `Dispatchers.IO` の仕事。

**修正方針**: scope を `Dispatchers.IO` に変更 (1 行)。`emulator.feed` の CPU 処理も同スレッドで問題ない。

### F-11 【確度: 中】書き込みキュー溢れで入力が黙って欠落する

- `ssh/SshSessionManager.kt:34` — `KChannel<ByteArray>(capacity = 256, onBufferOverflow = SUSPEND)`
- 同 `:40-41` — 送信は `trySend` なので SUSPEND 指定は無意味で、**満杯なら drop** (警告ログのみ)

大きなテキストの貼り付けや高速なキーリピートで 256 チャンクを超えると、送信バイトが欠落する。SSH ストリームの途中欠落なので、リモート側から見ると「入力が化ける/コマンドが壊れる」不安定動作になる。

**修正方針**: `capacity = UNLIMITED` にする (入力データ量は人間由来で有限、メモリリスクは実質無い)。もしくは呼び出し元を suspend 化して背圧をかける。

### F-12 【確度: 高 (実測未済)】Doze で必ず切断される設計ギャップ

- FGS はあるが wakelock なし・Doze 除外なし (UI 監査で確認: `WAKE_LOCK` 権限もコードも無い)
- Doze 中はネットワークが遮断されるため keepalive 応答が途絶え、sshj の KeepAlive 失敗 → transport 死 → `TerminalSessionController.kt:119-123` で Disconnected 止まり。再接続なし

画面を消して放置 → Doze 突入 → セッション死、が現状の必然。tmux 無しホストでは作業内容も失われる。「放っておくと落ちる (切れる)」のもう一つの顔。

**修正方針 (設計判断が必要)**: 選択肢は
(a) `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` をユーザー opt-in で要求 (電池とのトレードオフを設定画面で明示)、
(b) Doze 切断を受け入れ、フォアグラウンド復帰時の自動再接続 + tmux 再アタッチを完備する (推奨。F-04/F-07 と同じ仕組みに乗る)、
(c) 両方を設定で選ばせる。
また `ConnectivityManager.NetworkCallback` で網切替 (Wi-Fi↔LTE) を検知し、即座に dead 判定 + 再接続候補にする。

### F-14 【確度: 低 (潜在地雷)】EMPTY_CELL センチネルの破壊リスク

- `terminal/emulator/TerminalEmulator.kt:536,549` — `deleteChars`/`insertChars` が `buffer.cellAt(...).copyFrom(...)`
- `terminal/emulator/TerminalBuffer.kt:269` — `cellAt` は境界外で共有シングルトン `EMPTY_CELL` を返す

境界外アクセスが起きた場合に共有センチネルを書き換え、**全タブの空セル描画が壊れる**。現状のコードパスでは到達しない計算だが、防御として `cellAt` の戻りを書き換える箇所は境界チェック済みの直接アクセスに変えるべき。

### F-15 【確度: 情報】UI スレッドからのロック無し読み取り 6 箇所

feed/resize/draw の主経路は emulator monitor で正しく排他済み (`TerminalEmulator.kt:123,165` / `TerminalView.kt:306`)。ただし UI スレッドがロック無しで読む箇所が 6 つある (いずれも非クラッシュ設計だが JMM 的には data race):
`TerminalView.kt:378` (scrollbackSize), `:900-918` (選択テキスト抽出), `:607,615` (マウストラッキングフラグ), `:937` (application cursor keys), `:498-499,654-655` (カーソル/セル座標)。
費用対効果が高いのは `:900-918` と `:378` を `synchronized(ctl.emulator)` で包むこと。

---

## 4. 実装前にやるべき実測 (検証手順)

```bash
# 1. 過去のプロセス終了理由 — どの仮説が実際に起きているか一発で判る
adb shell dumpsys activity exit-info app.anoterm

# 2. クラッシュ直後の logcat (F-01/F-02 の例外名がそのまま出る)
adb logcat -b crash -d | grep -A 30 anoterm

# 3. 電池: 1 日使った後に
adb shell dumpsys batterystats --charged app.anoterm | head -100
#   → mobile radio active / wakeups の項を確認 (F-04 の定量化)

# 4. Doze 再現 (F-12)
adb shell dumpsys deviceidle force-idle
#   → keepalive 失敗 → 切断までの挙動と、復帰時 (unforce) の UI を確認

# 5. 6 時間テスト (F-01): Android 15+ 実機で接続したまま画面オフ 6h+ 放置
#    → exit-info に FGS timeout 起因のクラッシュが記録されるはず

# 6. メモリ (F-13)
adb shell dumpsys meminfo app.anoterm
```

補助として、`AnotermApp.onCreate` で `ActivityManager.getHistoricalProcessExitReasons()` を読んで
Logger.w に前回終了理由を出す仕込みを最初に入れると、以後の調査が全部楽になる (数行で済む)。

## 5. 修正の推奨順序

| 優先 | 項目 | 規模感 |
|---|---|---|
| P0 | F-01 FGS type を specialUse 化 + onTimeout 安全弁 | 小 (manifest + Service 数十行) |
| P0 | F-02 FGS 起動を接続開始時に前倒し + catch | 小 |
| P0 | F-03 START_NOT_STICKY + 空セッション時 stopSelf | 極小 |
| P0 | F-05 connect の cleanup + CancellationException rethrow | 小 |
| P0 | F-08 受信ループを Dispatchers.IO へ | 極小 (1 行) |
| P1 | F-06 dispose/close を IO へ | 小 |
| P1 | F-04 keepalive 設定化 (+ tmux ホストの背面切断ポリシー) | 中 |
| P1 | F-07 全滅検知 → FGS 停止 / 通知の実態反映 + 切断アクション | 中 |
| P1 | F-11 書き込みキュー UNLIMITED 化 | 極小 |
| P1 | F-10 resize 差分ガード | 小 |
| P1 | F-09 bell 一本化 + collect の repeatOnLifecycle 化 | 小 |
| P2 | F-12 再接続 + tmux 自動再アタッチ (設計判断込み) | 大 |
| P2 | F-13 scrollback メモリバジェット / Cell のプリミティブ化 | 大 |
| P2 | F-14, F-15 防御的修正 | 小 |

## 6. 修正効果の見込み (正直な評価)

**P0 だけで「劇的」と言えるのはクラッシュ面。** F-01 は決定論的なクラッシュ (6 時間放置で必ず発生) なので、
修正すれば「放っておくと落ちる」はほぼ消えるはず。F-08 (1 行) で多タブ時のフリーズも解消。
F-02/F-03/F-05/F-06 でクラッシュ経路とリーク経路が閉じる。

**電池面は P0+P1 で大幅改善するが、上限は設計判断で決まる。** ゾンビ FGS とリーク接続 (F-03/F-05/F-06)
を塞ぐだけでも「何もしていないのに減る」ケースは大きく減る。ただし *正常に* セッションを維持している間の
消費は keepalive 間隔 (F-04) とバックグラウンド維持ポリシー (F-04-2) の設計判断そのもので決まる。
「接続は維持したい / 電池も食いたくない」は物理的に両立しないので、**設定でユーザーに選ばせる**のが正解。

**体験としての安定性には F-12 (自動再接続 + tmux 再アタッチ) が不可欠。** Doze による切断は Android の
仕様であり修正では消せない。「切れない」を目指すのではなく「切れても戻った瞬間に自動で繋ぎ直り、
tmux で作業が無傷」を作ることが、このアプリにおける安定性の完成形。P2 だが体験インパクトは最大。

修正前後で `dumpsys activity exit-info` / `batterystats` を比較し、効果を数値で確認すること (§4)。

## 7. 追加バックログ A: 安定性の基盤 (S 系列)

### S-01 【小・最優先】起動時に前回のプロセス終了理由をログする
`AnotermApp.onCreate` で `ActivityManager.getHistoricalProcessExitReasons()` を読み `Logger.w` へ。
以後のフィールド調査が全部楽になる。§4 の手動 dumpsys の常設版。

### S-02 【小】未捕捉例外のローカル記録
`Thread.setDefaultUncaughtExceptionHandler` で stacktrace を `filesDir/crash/` に書いてから既定ハンドラへ
委譲。設定画面に「クラッシュログを共有」を追加。ストア外配布 (テレメトリ無し) の現状では唯一の実地診断手段。

### S-03 【極小】debug ビルドに StrictMode
`detectNetwork().penaltyLog()` + `detectLeakedClosableObjects()`。F-06 のようなメインスレッド I/O 違反や
socket/stream の close 漏れを開発中に自動検出できる。今回の調査で見つけた類のバグの再発防止。

### S-04 【小】release ビルドの定期スモークテスト
`isMinifyEnabled = true` + sshj/BC のようなリフレクション常習ライブラリの組合せは release 限定クラッシュの
温床 (proguard-rules.pro は現状よく書けているが、依存更新のたびに崩れうる)。最低限
「release APK で SSH 接続 → tmux attach → 切断」を依存更新時に必ず通す。CI (GitHub Actions で
`assembleRelease` + unit tests) があるとなお良い。

### S-05 【中】SecretStore の Keystore 障害耐性
`data/secrets/SecretStore.kt:22-24` は deprecated の `androidx.security.crypto` (MasterKey/EncryptedFile) に
依存。OS アップデートや機種移行で Keystore の鍵が無効化されると復号が例外を投げ、接続時に謎の失敗になる。
(1) 復号例外を catch して「認証情報を再登録してください」と案内する UI、(2) 中期的には Keystore +
AES-GCM の自前実装 (数十行) へ移行し deprecated 依存を外す。

### S-06 【小】ホスト鍵変更時のエラーを専用 UI に
`ssh/HostKeyVerifier.kt:49-52` — 鍵変更時は `false` を返すだけで、ユーザーには汎用の「接続失敗」しか
見えない (コメントにも Phase 9 予定と記載)。サーバ再構築のたびに「壊れた」と感じる UX。新旧指紋を
並べて表示し「信頼して更新 / 中止」を選ばせるダイアログ + KnownHostsScreen への導線を実装する。
なお現状の TOFU (初回自動信頼) は v1 方針としては妥当。

### S-07 【中】セッション層のユニットテスト
テストは VT エミュレータ系 4 本のみ。今回のバグ密集地帯 (SessionBundle の write ループ、
SessionManager のライフサイクル、connect のキャンセル安全性、再接続ロジック) は
`TerminalChannel` がインタフェースなので fake で普通にテストできる。P0/P1 修正と同時に書くこと。

## 8. 追加バックログ B: 使い勝手 (U 系列)

実装済みで手を入れなくてよいもの: ピンチズームによるフォントサイズ変更 (`TerminalView.kt:91-97`、
永続化も済み)、ハードウェアキー処理 (`TerminalView.kt:410`)、タブ複数同時接続、tmux 統合、
クリップボード履歴、カスタムショートカットバー、アプリロック、メモパッド。

### U-01 【大・体験インパクト最大】自動再接続 + tmux 自動再アタッチ
F-12 と同一。切断検知 (`connectionState`) → フォアグラウンド復帰時 or 手動タップで、保存済み認証情報で
再接続し、`useTmux` ホストは自動で `tmux new -A` を再送。指数バックオフ + `NetworkCallback` での網復帰
検知まで含めると完成form。現状の「Retry ボタンで新規接続し直し」から「勝手に治っている」への転換。

### U-02 【小】通知の情報量とアクション
「AnoTerm SSH 実行中 / タップして戻る」固定 (`SshForegroundService.kt:76-77`) を、
接続数・切断済み数の実態表示 + 「すべて切断」アクションボタンに。F-07 の修正と同時に実装するのが効率的。

### U-03 【小】プロセス死後のセッション復帰導線
`lastTabId` は保存している (`TerminalScreen.kt:169`) が `Navigation.kt:30-33` で意図的に未使用。
全自動復元はしない方針のままでよいが、ホスト一覧の先頭に「前回のセッションに再接続」カードを
1 枚出すだけで、LMK/クラッシュ後の復帰が 1 タップになる。

### U-04 【中】ポートフォワード (local / remote / dynamic)
未実装 (コードに痕跡なし)。sshj は `newLocalPortForwarder` 等で対応済みなので、ホスト設定に
フォワード定義を追加 + FGS 生存中はフォワードを維持、が素直な形。SSH クライアントとしては定番機能。

### U-05 【大】SFTP / ファイル転送
未実装。sshj に SFTPClient があるので技術障壁は低い。スマホ⇔サーバのファイル受け渡しは
モバイル SSH クライアントの主要ユースケース。SAF (Storage Access Framework) 連携で実装。

### U-06 【中】スクロールバック内検索
未実装。2000 行の履歴があるのに検索手段がない。TerminalBuffer を UI スレッドから読む際は
F-15 の同期ガード (synchronized(emulator)) を踏襲すること。

### U-07 【小】URL 検出とタップで開く
ログや CI 出力の URL をブラウザで開けるように。選択機能 (実装済み) の拡張として、
長押しメニューに「URL を開く」を足すのが最小実装。

### U-08 【小】セッションログの保存 (opt-in)
受信ストリームをファイルへ tee する。障害調査 (サーバ側の) にも役立つ。機密を含むため
明示 opt-in + アプリロック配下に置く。

### U-09 【中】バックグラウンド動作ポリシーの設定ページ
F-04/F-07/F-12 で導入する各ノブ (keepalive 間隔、背面切断までの猶予、自動再接続 on/off、
電池最適化除外のリクエスト) を 1 ページに集約し、それぞれの電池影響を説明文で明示する。
「電池を食う」問題の最終形はユーザーが自分のトレードオフを選べること。

### U-10 【小】セルフ診断画面
通知権限の有無、電池最適化の対象か、FGS が動いているか、各セッションの keepalive 状態を
一覧表示する「診断」画面。サポート不能なフィールド問題 (機種固有の kill 等) の切り分けに効く。
Don't kill my app (dontkillmyapp.com) 的な機種別注意書きへのリンクもここに。

## 9. Opus/Sonnet への実装ガイダンス

- 着手順は §5 の表 → S-01/S-02/S-03 → U 系列。P0 は互いに独立なので 1 コミットずつ分けること。
- F-01 (specialUse 化) は Play 提出時の申告が要る点を PR 説明に残すこと。
- F-04/F-12/U-01/U-09 は同じ「接続ライフサイクル管理」の設計に属する。個別にパッチせず、
  `SshSessionManager` に接続ポリシー層 (状態機械: Connected / Reconnecting / Suspended / Dead) を
  足す設計で一括対応するのが正しい altitude。
- 修正のたびに §4 の実測 (exit-info / batterystats) で before/after を取り、レポートに追記すること。
- メモリ `feedback_android_socket_io` の原則 (socket I/O は必ず IO ディスパッチャー) を close 経路にも適用。

## 10. 調査済みでシロだった項目 (再調査不要)

- **sshj `ssh.timeout = 15_000` (SO_TIMEOUT) はアイドル切断を起こさない**: sshj 0.40.0 の
  `transport/Reader.java` は `SocketTimeoutException` を catch して `continue` する実装を確認済み。
- **feed / resize / draw のデータ競合**: emulator monitor (`@Synchronized` + `TerminalView.kt:306` の
  `synchronized(ctl.emulator)`) で正しく直列化されており、resize×feed の AIOOBE や scrollback の
  ConcurrentModificationException の経路は存在しない。
- **カーソル点滅タイマー等の常時アニメーションループは無い** (カーソルは常時点灯方式)。
- **wakelock / KEEP_SCREEN_ON の取りっぱなしは無い**。
- **POST_NOTIFICATIONS は `MainActivity.kt:33,61-68` で要求済み**。
- **描画の invalidate は適切に coalesce されている** (`redrawSignal` DROP_OLDEST + `TerminalView.kt:483-491`)。
- **alternate screen では scrollback を積まない** (vim/tmux 中の履歴肥大は無い)。

---

## 11. 実装ログ (2026-07-07 第 1 弾)

debug ビルド (`assembleDebug`) + `testDebugUnitTest` 通過を確認済み。以下は今回のセッションで
適用した変更。行番号は変更後を指すとは限らないため、ファイル名 + 関数名で参照すること。

### 実装済み

| ID | 変更ファイル | 内容 |
|---|---|---|
| F-01 | `AndroidManifest.xml`, `ssh/SshForegroundService.kt` | FGS を `dataSync` → `specialUse` に変更 (6h タイムアウト強制クラッシュを回避)。`<property>` で用途宣言。`onTimeout()` を override し、万一時は `closeAll()` + `stopSelf()` する安全弁を追加。permission も `FOREGROUND_SERVICE_SPECIAL_USE` に。 |
| F-02 | `ssh/SshForegroundService.kt` (`start`), `MainActivity.kt` (`onStart`) | `start()` を `runCatching` で包み、バックグラウンド起動拒否でクラッシュしないように。`MainActivity.onStart` で生存セッションがあれば FGS を再起動する復帰時リトライを追加。 |
| F-03 | `ssh/SshForegroundService.kt` (`onStartCommand`) | `START_STICKY` → `START_NOT_STICKY`。空セッションで start された場合は即 `stopSelf()` し、ゾンビ FGS を防止。 |
| F-05 | `ssh/SshChannel.kt` (`connect`), `ui/terminal/TerminalScreen.kt` (connect の catch) | `connect()` 全体を try/catch で包み、失敗・キャンセル時に `ssh.disconnect()`/`close()` してリークを解消。`TerminalScreen` の catch 先頭で `CancellationException` を rethrow。 |
| F-06 | `ssh/SshSessionManager.kt` (`SessionBundle.dispose`, companion) | `channel.close()` を共有 `teardownScope` (Dispatchers.IO) に逃がし、メインスレッドでの network I/O (DISCONNECT 未送出→リーク) を解消。 |
| F-07 (部分) | `ssh/SshForegroundService.kt`, `ssh/SshSessionManager.kt` (`closeTab`) | 通知本文をセッション数表示に変更 + 「すべて切断」アクション追加。タブ増減時に通知を最新化。※「全 Disconnected 検知で自動停止」は接続ポリシー層 (F-12) と一緒に次弾。 |
| F-08 | `terminal/TerminalSessionController.kt` | 受信ループの scope を `Dispatchers.Default` → `Dispatchers.IO`。多タブ時の Default プール枯渇によるフリーズ/ANR を解消。 |
| F-09 | `terminal/compose/TerminalHost.kt`, `ui/terminal/TerminalScreen.kt` | bell の触覚を TerminalHost 1 経路に集約 (TerminalScreen 側の非スロットル collect を削除)。redraw/bell を `repeatOnLifecycle(STARTED)` でラップしバックグラウンド駆動を停止。復帰時に 1 回 invalidate。 |
| F-10 | `terminal/TerminalSessionController.kt` (`resize`) | 直近サイズを記憶し、同一サイズの resize では SIGWINCH を飛ばさない no-op ガード。再コンポーズ由来の SIGWINCH 連発を抑制。 |
| F-11 | `ssh/SshSessionManager.kt` | 書き込み `Channel` を `capacity=256/SUSPEND` (実際は trySend で drop) → `UNLIMITED`。大きな貼り付け時の入力欠落を解消。 |
| S-01 | `AnotermApp.kt` (`logLastExitReason`) | 起動時に `getHistoricalProcessExitReasons` を w ログへ。フィールドでの「勝手に落ちた」の終了理由が logcat に残る。 |

### 次弾に残した項目 (未着手・要設計判断)

- **F-04 keepalive 間隔の設定化** — 現状 `SshChannel.kt` で 30s ハードコードのまま。設定項目化 + tmux
  ホストの背面切断ポリシーは F-12 と同じ接続ライフサイクル層で実装するのが筋。
- **F-12 自動再接続 + tmux 自動再アタッチ** (= U-01) — `SshSessionManager` に接続ポリシーの状態機械
  (Connected / Reconnecting / Suspended / Dead) を新設し、F-04/F-07 残り/U-09 とまとめて実装する。
  **これが体験としての安定性の本丸。** §9 の設計方針を踏襲すること。
- **F-13 scrollback メモリバジェット / Cell のプリミティブ化** — 多タブ×横長での LMK 対策。
- **F-14 EMPTY_CELL センチネル防御 / F-15 UI 直読の synchronized 化** — 防御的修正。小。
- **S-02〜S-07, U-02〜U-10** — §7 / §8 参照。S-02 (未捕捉例外のローカル記録) と S-03 (debug StrictMode)
  は小さく効果が高いので次に着手すると良い。

### 実装後にやるべき検証

§4 の実測手順で before/after を比較すること。特に F-01 の効果確認には Android 15+ 実機で
6 時間超の放置テストが必要 (`dumpsys activity exit-info` に FGS timeout クラッシュが出なくなるはず)。
