# wanoterm 改善メモ

本ファイルはユーザとのやり取りで挙がった改善点・私が気づいた改善点を網羅する作業用メモ。
**各項目は「何をどう変えるか」「どのファイルに触るか」「完了判定」まで分解して書く**。
定期的に読み返して取りこぼしを防ぐ。

## 凡例

- [x] 完了（実機検証済 / コード反映済）
- [~] 完了（コード反映済、実機未検証）
- [ ] 未着手
- [!] 既知の制約・先送り

---

## A. 基本動作 / IME / 入力

- [~] **大文字化を止める** — `TerminalInputConnection.getCursorCapsMode` を常に 0 返し。完了判定: `android` と打って `Android` にならない
- [~] **Japanese IME backspace 1 文字ずつ消える** — ComposingState.resolveCursor は Android 仕様どおりの計算。完了判定: 「こんにちは」→ 5 回 backspace で 1 文字ずつ消える
- [ ] **emoji 合字（😀🇯🇵）の backspace でサロゲートペアが半分だけ消える問題** — `TerminalInputConnection.deleteSurroundingText` を code point 単位に変更。touch: `TerminalInputConnection.kt:155-176`
- [~] **IME 切断時に composing がゴミで残る** — closeConnection() で clear。完了判定: IME 切替直後にカーソル下にアンダーライン残骸が無い
- [~] **上下矢印が効かない** — ESC プレフィックス復活（0x1B バイト明示）。完了判定: shell で history 遡れる
- [ ] **ESC プレフィックスを 0x1B + "[A" の文字列リテラルにする保守性改善** — `byteArrayOf(ESC, LBR, 'A'.code.toByte())` で記述統一。新規に文字列リテラル書くと Edit/Write で ESC が消えるリスクあり。完了判定: grep "\"\\[[A-Z0-9~]*\"\\.toByteArray" で 0 件
- [~] **Ctrl+英字 を制御文字 (Ctrl+C=0x03 等) に変換** — `onKeyDown` で metaState.isCtrlPressed を見て計算。完了判定: 物理 KB で Ctrl+C 送れる
- [!] **Ctrl+矢印（単語ジャンプ）・Shift+矢印（選択）** — CSI 1;N 形式で送出実装済だが hw KB 実機未検証。完了判定: bash で Ctrl+Left/Right 動作
- [ ] **Shift+Tab = back-tab (ESC [ Z)** — 実装済だが hw KB 検証要
- [ ] **Alt+文字 = ESC+文字（meta prefix）** — vim 等で使う。実装 + 検証
- [ ] **HW KB 日本語 IME の onKeyMultiple 対応** — `TerminalInputConnection.sendKeyEvent` の ACTION_MULTIPLE 分岐は入ってる。HW KB で日本語打って検証
- [ ] **カーソルキー送出が DEC mode（?1 set）で変わる対応** — application cursor mode で ESC O A 形式に切り替え
- [ ] **long-press でコピペの selection UI** — 現状なし。TextSelectionToolbar 相当を実装。touch: `TerminalView.onTouchEvent`
- [ ] **clipboard にコピー** — selection 対応が前提
- [ ] **clipboard からペースト** — `TerminalView` に paste 機能追加、bracketed paste mode 有効時は ESC[200~ ... ESC[201~ で包む

## B. タブ / ナビゲーション

- [~] **タブ 3+ で入力できなくなる** — `terminalViews` を MutableStateMap<tabId, View> で追跡、currentTabId の view に LaunchedEffect で focus 要求。完了判定: 4 タブ作って順に切替して全タブで打てる
- [~] **同じホストタップで既存タブ再利用** — tabId を `host:${id}` 固定。完了判定: 同じホストを 2 回タップしてもタブ 1 個のまま
- [~] **長押しで新規接続タブ** — combinedClickable で onLongClick 設定。完了判定: 長押しで tabId に timestamp 付きで新タブ
- [~] **タブの × 誤タップで閉じる事故** — × のタップ領域 32dp に拡大 + 行全体長押しでも閉じる
- [~] **タブタイトルが "2" 等の不親切表示** — `hostDao.findById(id).label` を引くキャッシュを持つ
- [~] **app 切替→戻りで HOME 画面に戻る** — Navigation.kt の initial backstack で activeTabIds().firstOrNull() を Terminal として追加
- [ ] **プロセス kill 後に最後のタブに戻る仕組み** — Foreground Service で延命（着手中）+ 最後の tabId を DataStore に保存し復帰時に自動再接続
- [ ] **タブ閉じ確認ダイアログ（重要接続時）** — 3 秒以内の誤タップなら実行、そうでなければ確認
- [~] **戻る矢印で SSH が切断される** — SshSessionManager.get の isAlive 自動 dispose を削除
- [~] **HOME→戻りで IME 復活しない** — onWindowFocusChanged で isAttachedToWindow && hasWindowFocus なら requestInputFocus
- [~] **ctrl armed がタブ切替をまたいでリーク** — LaunchedEffect(currentTabId) で false にリセット
- [ ] **タブ一覧を別画面で見る (タブドロワー)** — タブが多すぎる時に上部の横スクロールバーだと使いにくい
- [ ] **タブ並び替えをドラッグで** — TabBar に reorderable
- [ ] **Pager 横スワイプが描画 View に食われてないか検証** — `onTouchEvent` の返り値調整済だが実機確認

## C. レンダリング / 描画 / エミュレータ

- [~] **Claude Code の BB 化**（DCS の payload がテキスト化していた疑い）— DCS/APC/PM/SOS state を追加、Cons until ST/BEL。要検証: Claude Code TUI で罫線・入力欄に B が残らないか
- [~] **全角セルの背景色が右半分塗られない** — bgOfCell が continuation のとき左隣の wide cell の bg を引く
- [~] **描画を 2-pass 化** — 背景ラン描画 → グリフ描画。wide char / color run が安定
- [~] **EAW 範囲拡充** — 0x1F100-0x1FBFF の emoji ブロック全部 wide、⬛ ⭐ 等個別追加
- [~] **残骸ピクセル（セル数 * セルサイズ < view 寸法の端数領域）** — view 実寸で全面塗り
- [~] **cellAt の @Synchronized lock 取得過多** — grid ローカル参照 + 配列 size での bounds check に変更
- [~] **毎グリフ String(Character.toChars) 生成** — LruCache(4096) コードポイント→String
- [ ] **frame snapshot 方式**（根本対策）— render 開始時に `buffer.snapshot()` で IntArray + ColorArray を一括取得、描画中に emulator 側が feed しても影響しない。完了判定: Claude Code 連続描画でちらつき消失
- [ ] **emoji presentation selector U+FE0F / U+FE0E** — U+2601 ☁ + FE0F = ☁️ の幅 1→2 切替。width計算に組み込む
- [ ] **emoji ZWJ sequence**（U+1F469 + ZWJ + U+1F393 = 👩‍🎓）— 1 文字として合体レンダリング。Paint.hasGlyph で確認、無ければ順次描画
- [ ] **DEC line drawing character set (G0 = 0, G1 等)** — ESC(0 で切替、a-w 等を罫線に。現状は文字そのまま
- [ ] **selection / 長押し範囲選択 UI** — 長押し開始 → drag で範囲 → pop up でコピー
- [ ] **選択範囲の背景描画** — renderer で selection rect を highlight
- [ ] **URL 検出（正規表現）+ タップでブラウザ起動** — http(s)://... を見つけたら Intent.ACTION_VIEW
- [ ] **cursor 点滅** — 現状 cursorBlinkOn=true 固定。Coroutine で 500ms ごとに toggle
- [ ] **cursor style 設定** — block / underline / bar を選べるように
- [ ] **BEL (0x07) でバイブ** — 短い HapticFeedback
- [ ] **alternative screen buffer (ESC [?1049h/l)** — vim/less/tmux が使う。別 buffer に切替保存復元
- [ ] **DECSTBM (CSI r) scroll region** — top/bottom 行制限した scroll
- [ ] **OSC 0 / 2（ウィンドウタイトル）** — リモートが `ESC]0;title ESC\` を送ってきた時にタブタイトル反映
- [ ] **OSC 52（クリップボード）** — リモートから `ESC]52;c;BASE64 BEL` でクリップボード設定
- [ ] **OSC 10 / 11（色問い合わせ）** — DE が dark mode 対応するため
- [ ] **OSC 8（hyperlink）** — ESC]8;;url ESC\ link ESC]8;;ESC\ を URL として扱う
- [ ] **SGR の 4:1 (曲線下線)・58;2;r;g;b (下線色)** — 最新 VT
- [ ] **mouse reporting（SGR mode ESC[?1006h 等）** — vim/htop でマウス使えるように
- [ ] **bracketed paste mode (?2004) 完全対応** — 現状は受信側の DEC SET を無視。ペースト時の prefix/suffix 送信側も実装

## D. スクロール / スクロールバック

- [~] **縦スクロールできない** — scrollback 実装 + GestureDetector
- [~] **スクロール方向感覚と逆** — distanceY 符号反転（指を下に引いたら過去）
- [~] **入力後に底に戻らない** — sendBytes / toolbar onSend で scrollToBottom
- [~] **resize 後に scrollback 表示がガタガタ** — 各行 cols に合わせて詰め直し
- [ ] **フリック（速いスワイプ）で慣性スクロール** — OverScroller を使う
- [ ] **スクロール中に新出力が来たら scrollOffset を +1 してずらさない** — 新行数だけ scrollOffset 増加、画面の見える部分は固定
- [ ] **スクロールバック最大行数を設定可能に** — 現状 2000 固定、AppPrefs に追加
- [ ] **スクロールバー（右端の細い可視 indicator）** — 現在位置が一目で分かる
- [ ] **scrollback 内検索 (Ctrl+F)** — 上から順に hit → 次の hit へ

## E. パフォーマンス

- [~] **cellAt lock contention** — 上記対応済
- [~] **String allocation per glyph** — LruCache 済
- [~] **onDraw 毎フレーム log template 生成** — 削除済
- [~] **outputStream.flush 毎 write** — キュー drain してから 1 回に集約
- [ ] **scrollUp の Array.copy を行配列 pool 化** — 行配列を再利用して allocation 削減
- [ ] **bgOfCell の cellAt 二重呼び出し** — 1 行先取り IntArray snapshot で走査
- [ ] **resize 時 scrollback 全行 rebuild がメインスレッドブロック** — Coroutine で async
- [ ] **VT feed の処理を独立 Dispatcher へ** — Dispatchers.Default 固定を Dispatchers.IO に、あるいは専用 SingleThread
- [ ] **SSH 受信の backpressure** — yes コマンド等大量受信時に emulator.feed が追いつかない場合の対策
- [ ] **keepalive 30s の適正化** — モバイル NAT 30s 未満もあるので 15s に下げる検討
- [ ] **文字表示の measureText をキャッシュ** — 同じ glyph 幅を毎描画測るのは無駄
- [ ] **paint flag の条件付き setter 呼び出しを減らす** — isFakeBoldText 等、値が変わらないのに毎セル set している
- [ ] **canvas.save/restore のグリフ clip** — 現状全グリフで save/restore。本当に overflow するグリフのみに制限
- [ ] **redrawSignal の SharedFlow 設計見直し** — 現状 extraBufferCapacity=1 DROP_OLDEST、collector 無い時 drop される

## F. セキュリティ（Play Store 配布前）

### Logging / 機密情報

- [~] **Logger.d/i を BuildConfig.DEBUG で no-op**
- [~] **ProGuard -assumenosideeffects で Log.d/i/v と Logger.d/i を release で R8 が消す**
- [~] **SSH 送受信の hex dump / text dump を全削除**

### Storage / 鍵管理

- [~] **secrets を EncryptedFile + MasterKey (AES256-GCM)**
- [~] **allowBackup=false + backup_rules で secrets/db/prefs 除外**
- [~] **dataExtractionRules で cloud backup と device transfer の両方除外**
- [~] **秘密鍵 ByteArray を認証後 fill(0) でゼロクリア**
- [~] **passphrase char[] を認証後 fill(' ') でクリア**
- [ ] **AuthCredentials の passphrase を String から CharArray に変更** — immutable String から脱却
- [ ] **AuthCredentials.PrivateKey.keyBytes を使い捨て用の SecureByteArray でラップ** — close() で強制ゼロクリア
- [ ] **SecretStore のロード／ストア時に clearText ByteArray がヒープに残らない工夫** — DirectByteBuffer 使う検討

### ネットワーク

- [~] **Network Security Config + usesCleartextTraffic=false**
- [~] **TOFU host key verification**
- [ ] **Host key 変化時のユーザ確認ダイアログ** — 現状は即 reject、本当に変わった時の手動承認 UI
- [ ] **StrictHostKeyChecking=ask 的な動作を UI に出す** — 初回接続時のフィンガープリント表示

### App-level セキュリティ

- [~] **Biometric lock（任意）**
- [ ] **Biometric lock を常時 ON にする設定 UI** — 現状 prefs にあるか確認必要
- [ ] **App lock timeout** — バックグラウンド 5 分でロック等
- [ ] **AuthCredentials 表示時にキーボードを secure mode に** — 画面録画防止は flag 設定
- [ ] **FLAG_SECURE で screenshot / screen recording 防止** — 設定で ON/OFF 可能に

### Release / 配布

- [~] **エラーメッセージのサニタイズ** — username/host を UI に出さない
- [ ] **Release build での動作確認** (#53) — R8 後の sshj / BouncyCastle 問題ないか実機で
- [ ] **upload key の keystore 生成** — keytool で生成、~/.android/keystore に保存
- [ ] **signing config を gradle.properties 経由（~/.gradle/gradle.properties、git 管理外）** — 既存の build.gradle.kts で対応済だが keystore 用意してない
- [ ] **Play App Signing 有効化** — Play Console で upload key 登録、Google が app signing key 管理
- [ ] **Privacy Policy URL** — GitHub Pages で公開（wanoterm.github.io/privacy 等）
- [ ] **Data Safety フォーム記述** — Play Console でデータ収集 "none" を宣言
- [ ] **Play Integrity API** — 改造 APK の排除（任意）
- [ ] **Root 検出**（任意） — 拒否 or 警告
- [ ] **AndroidManifest の debuggable を release で明示 false** — default は false だが念のため
- [ ] **ProGuard mapping.txt を Play Console へ upload** — crash スタックトレース復号

## G. UI / UX / 画面構成

### 現画面の改善済

- [~] **接続状態 dot（緑/黄/赤/灰）** — TopAppBar のタイトル横
- [~] **ツールバー縮小 32dp**
- [~] **ショートカットバー展開トグル**
- [~] **キーボード閉じボタン**
- [~] **タブ長押しで閉じる / × 32dp**

### HostList 改善

- [ ] **ホスト検索ボックス** — 50 件超える時用
- [ ] **ホストをグループ化**（"work" "personal" 等） — HostEntity に group カラム追加
- [ ] **ホストアイコン/色** — 視覚識別
- [ ] **最終接続日時** — supportingContent に「5 分前」等
- [ ] **接続中ホストにバッジ** — 緑ドット

### Terminal 画面改善

- [ ] **フォントサイズ設定 UI** — 現状 pinch のみ、数値入力で指定
- [ ] **カラーテーマ追加** — Solarized Dark / Light / Gruvbox Dark / Nord / Monokai / One Dark
- [ ] **背景透過度** — 壁紙透かし
- [ ] **タブごとの色タグ** — 同じホスト複数タブの区別
- [ ] **下部ツールバー 2 段（展開時）** — 下段に F1-F12 等
- [ ] **カスタムショートカット設定を画面内から追加** — 現状 Settings 画面のみ

### 設定画面

- [ ] **既存の AppPrefs 項目を全部 Settings に露出** — テーマ / フォント / 改行 / 通知権限

### エラー / 状態表示

- [ ] **接続再試行ボタン** — エラー画面に
- [ ] **切断された時の通知** — Foreground Service 通知で表示
- [ ] **初回起動時のオンボーディング** — "1. ホスト追加 → 2. タップで接続 → 3. タブで切替" の 3 ページ

### Termius 同等を狙う大機能

- [ ] **Split terminal（1 画面に 2 shell 縦/横分割）**
- [ ] **Snippets（保存コマンドを tap で送信）** — `hostDao` に Snippet テーブル追加
- [ ] **SFTP（ファイル転送 UI）** — sshj の SFTPClient 使う
- [ ] **ポートフォワーディング UI** — Local / Remote / Dynamic
- [ ] **SSH Agent Forwarding** — ssh-agent 連携
- [ ] **Jump Host（ProxyJump 等価）** — 踏み台経由
- [ ] **Mosh 対応** — mosh server を SSH で起動して UDP へ
- [ ] **Cloud Sync**（任意） — Google Drive / Firebase に暗号化同期
- [ ] **Known hosts 管理画面（強化）** — 現状の KnownHostsScreen 拡充
- [ ] **history 強化** — 日時・成功/失敗・tag フィルタ

## H. フォント / 表示

- [~] **JetBrains Mono Regular/Bold バンドル** — res/font/ に配置、programFont として使用
- [~] **CJK は system fallback** — JetBrains Mono に無いコードポイントは Android が auto fallback
- [ ] **Noto Sans Mono CJK JP オプション同梱** — 最高品質派向け（~16MB、別 APK variant or download）
- [ ] **JetBrains Mono Italic variant** — currently Typeface.create(reg, ITALIC) で斜体化（実際の italic グリフと違う）
- [ ] **OpenType feature: Ligatures (=>, !=, -> 等)** — Paint.fontFeatureSettings = "liga"
- [ ] **太字は fake bold ではなく JetBrains Mono Bold に切替** — `programFontBold` 用意
- [ ] **フォント太さ選択（regular / medium / bold）**
- [ ] **フォント切替 UI** — system monospace / JetBrains Mono / Cascadia Code (同梱するなら)
- [ ] **行間調整** — cellHeight に padding 足せるように
- [ ] **アンチエイリアス on/off** — 小さい文字で off の方が読みやすい人向け

## I. エミュレータ互換性

- [~] **基本 CSI (CUU/CUD/CUF/CUB/CUP/EL/ED/SGR/DSR)**
- [~] **UTF-8 decoder**
- [~] **scrollback**
- [~] **DCS/APC/PM/SOS consume**
- [ ] **alternative screen (ESC[?1049h/l)** — 別 TerminalBuffer を持って switch。vim / tmux / less が使う
- [ ] **DECSTBM (CSI r) scroll region**
- [ ] **CSI S / T (SU/SD scroll up/down)** — 明示スクロール
- [ ] **DCS + q (DECRQSS)** — setting report
- [ ] **OSC 0 / 1 / 2 (set title)**
- [ ] **OSC 4 / 104 (palette set/reset)**
- [ ] **OSC 52 (clipboard)**
- [ ] **OSC 7 (current dir)** — エディタ連携
- [ ] **OSC 8 (hyperlink)**
- [ ] **Sixel graphics (ESC P q ...)** — 画像プロトコル
- [ ] **Kitty graphics protocol** — より新しい画像
- [ ] **Mouse reporting SGR mode (?1006)**
- [ ] **bracketed paste mode (?2004)** — 受信側 advertise
- [ ] **modifyOtherKeys (?formatted keyboard)** — 送信側実装済、advertise 側要
- [ ] **RIS (ESC c) の実装完全化** — 現状 reset() を呼ぶだけ、scrollback もクリアすべきか要判断

## J. 運用 / CI / Doc

- [ ] **GitHub Actions で PR 時に debug build + ktlint** — .github/workflows/ci.yml
- [ ] **GitHub Actions で release build（tag push 時）** — aab 生成、Play Console に upload
- [ ] **Unit test: TerminalEmulator** — CSI/SGR/CR/LF/resize の各ケース
- [ ] **Unit test: TerminalBuffer** — scrollUp / scrollDown / resize / scrollback の境界
- [ ] **Unit test: CharWidth** — 代表的 CJK / emoji / combining char
- [ ] **Unit test: Utf8Decoder** — 不正バイト列 / サロゲートペア / 先頭継続バイト
- [ ] **Instrumented test: IME** — setComposingText → finishComposingText の一連を spy
- [ ] **Instrumented test: Loopback での scroll / input** — end-to-end
- [ ] **README.md** — スクリーンショット / 機能 / ライセンス / 貢献手順
- [ ] **CHANGELOG.md** — バージョンごとの変更点
- [ ] **LICENSE** — OFL (JetBrains Mono)、BSD (sshj)、Apache 2.0 (wanoterm) の明記
- [ ] **THIRDPARTY.md** — 依存ライブラリと license の列挙
- [ ] **リリースフロー document** — keystore 管理・Play Console 申請手順
- [ ] **ユーザ向け /help 画面の充実** — VT 互換範囲 / ショートカット一覧 / Tips
- [ ] **Microbenchmark** — 描画 / emulator.feed の FPS / allocation 測定

## L. TopAppBar の 3 アイコン（history / tmux / help）についての棚卸し

現状 TopAppBar 右側に 3 つアイコンがある: history（時計）・tmux（dashboard）・help（?）。本当に全部必要か検証。

- [ ] **history アイコンの中身**: `HistorySheet` を開き、`CommandHistory` が SSH に送った行を出す。保存件数 50、enter で 1 行として確定、`observe()` で bytes → 行アセンブル。**保存されるのはユーザが入力した文字列のみ**（サーバからの echo / 出力は含まない）。ユーザが「このコマンドを再実行したい」時に便利。

  - [ ] **history に「SSH に送ったものだけが本当に保存されてるか」確認する** — Ctrl+C や Esc のような非文字入力も混ざってるか要確認
  - [ ] **history をタブ間で共有 or 別々か明確化** — 現状は TerminalSessionController ごとなのでタブごと独立
  - [ ] **history を永続化**（現状メモリのみ、プロセス kill で消える）— SharedPreferences or Room に保存
  - [ ] **history のフィルタリング / 検索 UI** — 現状は単純リスト

- [ ] **tmux アイコンの中身**: `TmuxPanel` を開く。現状 tmux の prefix (Ctrl-B) と主要キー（n 新 window / , rename / p 前 / d detach / w window-list / % 縦分割 / " 横分割 等）を並べてワンタップ送信する補助パネル。**純粋に送信補助なので機能としては動いている**が、ツールバーの ⌃B 1 つで代替できることも多い。

  - [ ] **tmux パネルの利用ログ**（どのキーが押されたか）で使われてなければ削除 / ⌃B だけにまとめる
  - [ ] **tmux prefix キーを設定可能に**（Ctrl-B → Ctrl-A 派も多い）
  - [ ] **tmux のペイン分割後にすぐ編集できるよう、パネル閉じ自動**

- [ ] **help アイコンの中身**: `HelpSheet`。ショートカット一覧や VT 互換の簡単な説明。情報がそこまで厚くないなら統合しても良い。

### 提案: 3 アイコンを 1 つの「⋮ メニュー」にまとめる

- [ ] **TopAppBar アクションを 1 つのオーバーフローメニューに統合** — 3 アイコンを 1 個に、タップで「履歴 / tmux / ヘルプ / デバッグ報告 / 設定」のリスト。画面横幅節約。

## M. デバッグレポート機能（実機使用中にその場でメモ）

- [ ] **Room に DebugReportEntity** — id / timestamp / body / status(open/done) / contextTabId
- [ ] **DebugReportDao** — observeAll / insert / updateStatus / deleteById
- [ ] **ツールバー近くに「🐛」ボタン追加** — ショートカットバー右端 or TopAppBar
- [ ] **ボタンタップで BottomSheet** — 既存レポート一覧 + 新規作成 TextField + 保存ボタン
- [ ] **各レポートに「完了」トグル** — 修正済みマーク
- [ ] **レポートを外部ストレージに export** — `adb pull` で取り出せる場所に書き出し（`/sdcard/Android/data/com.example.wanoterm.debug/files/debug_reports.json`）
- [ ] **Claude 側（私）が pull して読む手順を docs に** — `adb pull` コマンド例
- [ ] **修正済みを自動削除 or アーカイブ** — 完了後にリストから消すか保持するか選択

## N. tmux ネイティブ統合（iTerm2 方式 `tmux -CC`）← 次の目玉機能

**方針**: iTerm2 の tmux integration と同じく、tmux の control mode (`tmux -CC`) を使って
リモートの tmux セッション全体を wanoterm のネイティブ UI にマップする。
現 TmuxPanel はキー送信 fallback として残す。

### Phase 1: 接続時の自動 attach（最小成果）

- [ ] **HostEdit 画面に「tmux integration」チェックボックス** — HostEntity に `useTmux: Boolean` カラム追加、DB migration（version 3）
- [ ] **接続成功後に `tmux -CC new -A -s wanoterm\r` を送出** — SshChannel 直後に 1 回、Base64-encoded keys option 付き
- [ ] **「tmux が使えない（未インストール／version 古い）」エラー検出** — `tmux: command not found` が返ってきたら fallback 表示
- [ ] **接続 label に "tmux" バッジ** — 統合モード動作中を視覚的に

### Phase 2: ControlMessage パーサ

- [ ] **`TmuxControlMessage` sealed class** — Begin/End/Output/WindowAdd/WindowClose/SessionChanged/LayoutChange/Exit 等
- [ ] **`TmuxControlParser`** — SshChannel の入力を `%` 始まり vs 通常 output で分岐、行単位で parse
  - [ ] `%begin <timestamp> <number>` / `%end` / `%error` のブロック境界
  - [ ] `%output %<pane_id> <data>` の data は escape 化されているので decode
  - [ ] `%window-add @<window_id>` / `%window-close`
  - [ ] `%session-changed $<sid> <name>`
  - [ ] `%layout-change @<wid> <layout>`
  - [ ] `%exit [reason]`
- [ ] **escape decoding** — tmux は制御文字を `\ooo` (octal) 形式で送る、復号必要
- [ ] **unit test**: 実 tmux セッションのログから fixture を作って parser を検証

### Phase 3: TmuxSession / TmuxWindow / TmuxPane モデル

- [ ] **`TmuxSession`** — control channel 管理、コマンド送信、メッセージ配信
- [ ] **`TmuxWindow`** — tmux window ≒ wanoterm タブ
- [ ] **`TmuxPane`** — tmux pane ≒ 画面分割領域
- [ ] **TerminalSessionController を分割**: 1 つは tmux 全体 control、pane ごとに個別の emulator/buffer を持つ
- [ ] **pane の write** は tmux の `send-keys -l -t %<pane_id> <data>` でラップして送る（または `paste-buffer`）
- [ ] **resize は refresh-client -C <cols>x<rows>** で tmux に通知、tmux 側が layout 再計算

### Phase 4: UI レイヤ

- [ ] **tmux window を wanoterm タブに同期** — `%window-add` でタブ生成、`%window-close` でタブ削除
- [ ] **tmux pane を split layout で描画** — Compose の Row/Column + Weight で tmux の layout string を解釈
- [ ] **layout parser** — `Nx{M,a,b{c,d,e}}` 形式を Compose のネスト Row/Column に変換
- [ ] **pane フォーカス表示** — アクティブ pane を枠線でハイライト
- [ ] **pane タップで切替** — タップした pane に focus、IME をそこへ
- [ ] **pane ごとに独立した TerminalView + IME** — 各 pane が自分の InputConnection 持つ
- [ ] **pane の scrollback は per-pane**（tmux の `capture-pane -p` または wanoterm 側の emulator が記録）

### Phase 5: 状態管理 / ライフサイクル

- [ ] **detach-attach**: UI で detach ボタン → `detach-client`、次回接続時に `-A` で自動 re-attach
- [ ] **kill-session**: UI からセッション終了
- [ ] **new window / split pane UI** — 指でタップだけで window/pane 追加できるよう
- [ ] **切断復帰**: SSH 切断から再接続時に tmux が生きていれば自動 attach、無ければ新規

### Phase 6: 機能拡張

- [ ] **tmux コマンド palette** — `rename-window` `swap-window` 等を UI で
- [ ] **ペインのコピーバッファをシステムクリップボードに連携**（OSC 52 経由）
- [ ] **ペイン間のドラッグ&ドロップで並び替え**
- [ ] **tmux の mouse-mode を活用**（pane 切替を tmux 内イベントで）

### リリース戦略

- [ ] **Play Store の打ち出し文**: 「Android で唯一、tmux をネイティブ UI で扱う SSH クライアント（iTerm2 方式の control mode 統合）」
- [ ] **スクショ**: tmux 経由で vim + shell + log tail が 3 分割で動く絵
- [ ] **ヘルプ**: tmux 統合の on/off 切替・使い方説明

### 参考情報

- iTerm2 tmux integration: <https://iterm2.com/documentation-tmux-integration.html>
- tmux control mode documentation: `man tmux` の CONTROL MODE 章
- tmux source: `control.c`, `control-notify.c`
- iTerm2 側の実装 (オープンソース): `iTermTmuxController.m` など

## O. マネタイズ（Freemium + 買い切り Pro $6.99）

### O-1. Free / Pro の切り分け実装

- [ ] **Pro フラグを AppPrefs に追加** — `isPro: Boolean`、StateFlow で全画面に伝搬
- [ ] **BillingClient 導入** — Google Play Billing Library 7.x、one-time product `wanoterm_pro_unlock`
- [ ] **起動時に Billing でエンタイトルメントを確認** — Play 側の purchase を毎起動チェック、オフライン時はキャッシュ
- [ ] **購入フロー UI** — Settings → 「Pro 版にアップグレード」→ Billing dialog 起動
- [ ] **復元ボタン** — 機種変更後の購入復元用
- [ ] **Pro ユーザ向けバッジ表示** — 「Pro」ラベル（ささやかに）
- [ ] **ProGuard keep** — Billing クラスは minify で潰れないように rules 追加

### O-2. Free で使える範囲（「基本 SSH クライアント」として成立）

- [ ] SSH 接続そのものは **無制限・無条件**（接続時間・回数制限なし）
- [ ] **ホスト保存上限 3 個** — HostListScreen で 4 個目保存時に upgrade ダイアログ
- [ ] **タブ同時 2 個** — 3 個目開こうとしたら upgrade ダイアログ
- [ ] **日本語 IME は完全 Free**（差別化点、制限禁止）
- [ ] **biometric lock / TOFU / Known hosts は Free**（セキュリティなので）
- [ ] **テーマ 2 種（Tokyo Night / Termius Dark）まで Free**
- [ ] **フォント: JetBrains Mono のみ Free**
- [ ] **広告は入れない**（Free でも）

### O-3. Pro unlock される機能（実装が必要な順）

- [ ] **ホスト数・タブ数の制限撤廃** — 実装: AppPrefs.isPro を参照するガード関数
- [ ] **tmux control mode 統合**（N セクション全体、Pro の最大の目玉）
- [ ] **SFTP ファイル転送 UI** — 別画面、sshj の SFTPClient
- [ ] **ポートフォワーディング** — Local/Remote/Dynamic、sshj で対応
- [ ] **SSH Agent Forwarding** — Pro
- [ ] **Jump Host (ProxyJump)** — HostEdit に「経由ホスト」選択
- [ ] **SSH 鍵認証を Pro 限定にするか要検討** — セキュリティ機能として Free に残す選択肢あり。要判断
- [ ] **テーマ Pro unlock**（Solarized / Gruvbox / Dracula / Nord / 自作）
- [ ] **フォント拡張** — Cascadia Code 等同梱、カスタム import
- [ ] **リガチャ on/off**
- [ ] **スニペット機能** — 保存コマンド集
- [ ] **履歴の永続化 + 検索** — Room に保存、fuzzy 検索
- [ ] **画面分割 (Split terminal)** — 1 画面に 2 shell
- [ ] **スクロールバック行数上限 2000 → 設定可（最大 10000）**
- [ ] **暗号化バックアップ export/import** — 機種変更用
- [ ] **セッションログ記録**（compliance）
- [ ] **自動再接続・スケジュール実行**
- [ ] **コマンドパレット (Cmd+K 相当)**
- [ ] **タグ・グループ分類**
- [ ] **per-host biometric lock**

### O-4. 将来の拡張（Pro 購入後のオプション）

- [ ] **wanoterm Cloud（E2EE 同期）** — 月 ¥300 subscription、複数端末間でホスト同期
- [ ] **wanoterm Team** — 月 ¥600/ユーザ、チームでホスト共有

### O-5. Play Store 申請周り（Pro 追加時）

- [ ] Google Play Billing の in-app product 登録
- [ ] Sandbox テスターでの動作確認
- [ ] Data Safety 更新（課金情報を Google に送信する旨記載）
- [ ] Privacy Policy 更新（課金情報 / Play レシート保存）

## P. 秘密鍵のセルフサービス作成＆ヘルプ

wanoterm を「Android だけで完結する SSH クライアント」として使えるようにする。
現状は「外部で作った秘密鍵を持ち込む」前提、これを改善。

### P-1. アプリ内で秘密鍵生成

- [ ] **SSH 鍵生成 UI** — Settings → 「SSH 鍵を作成」画面
  - [ ] 鍵種選択（ED25519 推奨デフォルト / RSA 4096 / ECDSA P-256）
  - [ ] コメント（メアド等）入力
  - [ ] passphrase オプション（空で通す）
  - [ ] 作成ボタンで生成 → SecretStore に保存
- [ ] **鍵生成の実装** — BouncyCastle の KeyPairGenerator（すでに依存済み）
  - [ ] ED25519: `KeyPairGenerator.getInstance("Ed25519", "BC")`
  - [ ] RSA 4096: `KeyPairGenerator.getInstance("RSA", "BC")` keysize=4096
  - [ ] PEM フォーマット書き出し（OpenSSH 形式推奨 → sshj が読める）
- [ ] **公開鍵を見る画面** — authorized_keys に追加するためにコピー
  - [ ] コピーボタン
  - [ ] QR code 表示（他端末へ送る用）
  - [ ] メール送信・メモ送信
- [ ] **鍵一覧画面** — 保存済み鍵をリスト、使用中のホストを紐付け
  - [ ] 鍵削除（紐付くホストが壊れる警告）
  - [ ] 鍵名変更
- [ ] **鍵 import（外部ファイル）** — SAF で .pub / id_rsa 等をインポート
- [ ] **鍵 export** — 機種変更・バックアップ用、暗号化 ZIP

### P-2. 秘密鍵ヘルプコンテンツ

- [ ] **Settings → SSH 鍵ヘルプ** で開く画面を用意
  - [ ] **そもそも SSH 鍵とは何か**（2-3 段落で）
  - [ ] **wanoterm 内で生成する場合の手順**（画面キャプチャ付き）
  - [ ] **外部で作った鍵を使う場合の案内**
    - macOS/Linux: `ssh-keygen -t ed25519 -C "your_email@example.com"`
    - Windows: PowerShell / PuTTYgen
    - Android: Termux 内で `ssh-keygen`、または JuiceSSH 等の既存ツール
  - [ ] **生成した公開鍵をサーバに登録する手順**
    - `~/.ssh/authorized_keys` への追記方法
    - `ssh-copy-id` の使い方（ただし Android からは困難）
    - AWS EC2 の場合の Launch wizard
    - GitHub / GitLab の場合の Settings → SSH Keys
  - [ ] **鍵の形式**（PEM vs OpenSSH、RSA/ED25519 の違いと推奨）
  - [ ] **passphrase の要否とメリット・デメリット**
  - [ ] **ロストした場合** — 復旧不能、サーバ側で authorized_keys から削除 + 再生成

### P-3. ヘルプ表示の実装

- [ ] **Settings 画面に「SSH 鍵の使い方」リンク**
- [ ] **HostEdit の認証方式選択で「秘密鍵」にしたときに「鍵がない？ここから作成」リンク**
- [ ] **Markdown レンダリングまたは静的 Compose スクリーン** — Markdown だと更新楽、静的だとビルド重くない

### P-4. 既存画面との統合

- [ ] **HostEditScreen で「鍵を選ぶ / 新規作成 / インポート」タブ**
- [ ] **鍵を使っているホストを鍵画面から逆引き**
- [ ] **鍵とパスワードで認証順 fallback**（公開鍵ダメならパスワード）

## K. 未確認の懸念（私の手抜き報告）

- [ ] "completed" 扱いしてるもの大半が実機検証なし — ユーザ叩いて OK もらうか adb でスクショ検証
- [ ] 水平/垂直判定のタッチ消費 (#35) の実ジェスチャ確認
- [~] Foreground Service の通知アイコン — `drawable/ic_notification_terminal.xml` モノクロ vector に差替え済、実機未検証
- [ ] タブ削除時の Compose 側 TerminalView 参照残留（弱参照化 / onRelease で明示クリア）
- [ ] 縦ドラッグと Pager 横スワイプの優先度、どこで取り合うか実機検証
- [ ] shortcut bar 展開時のアニメーション duration の体感
- [ ] app 復帰時の LaunchedEffect 発火順序（currentTabId 設定 → focus 要求のタイミング）
- [ ] 画面回転時の TerminalView 再作成 + 既存 SessionBundle の reuse が壊れてないか
- [ ] sshj 0.40.0 + AndroidKeyStore の互換性（特定ホスト鍵で reject されないか）

## Q. 2nd-audit 指摘の修正履歴

2 回目の監査で挙がった 11 件のうち「本番で即事故る」3 件 + 中段 4 件を即修正。

### 修正済

- [x] **#66 DB migration v1→v2 でホスト情報消失** — `AppDatabase.MIGRATION_1_2` を追加し `debug_reports` のみ作成、`hosts` / `known_hosts` は温存。`fallbackToDestructiveMigration(dropAllTables=true)` 撤去
- [x] **#67 Alt screen scrollUp で primary scrollback 汚染** — `TerminalBuffer` に `scrollbackEnabled` を足し、alternateBuffer は false で構築。vim/less スクロールが `hosts` の履歴を汚さない
- [x] **#68 Pro toggle の SharedPreferences 直書きで課金回避** — SettingsScreen の Switch を `BuildConfig.DEBUG` で囲み、release ではテキスト表示のみ。購入は Play Billing (#64) 経由で
- [x] **#69 POST_NOTIFICATIONS runtime request** — MainActivity で Android 13+ 向けに `ActivityResultContracts.RequestPermission` を起動
- [x] **#70 通知アイコン** — `drawable/ic_notification_terminal.xml` 新規、`SshForegroundService.setSmallIcon` 差替え
- [x] **#71 remoteTitles / tabLabels の stale entry** — `LaunchedEffect(activeTabs)` 冒頭で `alive` セット差分を削除
- [x] **#72 Ed25519 `pointEncoding` のサイズ検証** — `SshKeyGenScreen.toOpenSshPublic` で `check(encoded.size == 32)`

### 継続懸念（別タスクで後追い）

- [ ] **RIS (ESC c) の scrollback 挙動** — alt 画面中に RIS が来ると primary の scrollback が巻き添えで消える可能性。xterm 準拠だと保持すべき
- [ ] **DebugReport export 効率化** — 変更のたび全件を JSON に再書き出し、件数が増えると重い。追記方式に
- [ ] **Navigation3 backstack deserialize** — プロセス kill → 復帰で `Terminal(tabId)` が `@Serializable` 経由で復元される経路を実機で確認
- [ ] **BC provider の重複登録** — `WanotermApp.onCreate` の `removeProvider("BC")` → `insertProviderAt` が ConfigChange で重複起動しないか
- [ ] **DL (CSI M) が primary buffer の scrollback を汚染** — `TerminalEmulator:296` で `buffer.scrollUp` を呼んでおり、primary 側では delete-line が履歴に行を積んでしまう。alt 側は #67 でカバー済、primary は要別対応

---

## 作業の進め方

1. [~] → 実機で叩いて確認 → 問題なければ [x]
2. [ ] → 優先度順に消化（セキュリティ F > 基本動作 A > UI G > レンダリング C > スクロール D > 他）
3. 新たな気づきは随時この TODO.md に追加
4. **改善サイクルの終わりに必ずこの TODO.md を読み返す**
