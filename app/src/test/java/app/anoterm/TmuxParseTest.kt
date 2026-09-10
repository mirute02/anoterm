package app.anoterm

import app.anoterm.ssh.TmuxController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 区切りは 0x1F。ソースに生バイトを置くと編集で落ちるので、必ずエスケープで書くこと
 * （このリポジトリでは ESC を同じ理由で `0x1B` と明示している）。
 */
private const val S = "\u001F"

class TmuxParseTest {

  @Test
  fun readsClientsAndWindows() {
    val out =
        listOf(
                "ANOTERM_TTY_1=/dev/pts/3",
                "C${S}/dev/pts/3${S}work",
                "W${S}work${S}0${S}1${S}1${S}1789054933${S}claude${S}claude",
                "W${S}work${S}1${S}0${S}2${S}1789054000${S}bash${S}build",
            )
            .joinToString("\n")

    val snap = TmuxController.parseSnapshot(out, "ANOTERM_TTY_1")

    assertEquals(2, snap.windows.size)
    assertEquals("/dev/pts/3", snap.clientTty)
    assertEquals("work", snap.attached)
    assertEquals("claude", snap.windows[0].command)
    assertTrue(snap.windows[0].active)
    assertEquals(2, snap.windows[1].panes)
    assertEquals("build", snap.windows[1].name)
  }

  @Test
  fun keepsNamesThatContainSpaces() {
    // ウィンドウ名は任意の文字列。空白で切ると別の名前になる。だから区切りが 0x1F。
    val out = "W${S}work${S}0${S}1${S}1${S}0${S}vim${S}my notes"
    val snap = TmuxController.parseSnapshot(out, "ANOTERM_TTY_1")
    assertEquals("my notes", snap.windows.single().name)
  }

  @Test
  fun dropsLinesWithoutTheSeparator() {
    // ログインシェルの挨拶などが混ざっても、別のウィンドウとして読んではいけない。
    val out =
        listOf(
                "Welcome to Ubuntu 24.04 LTS",
                "Last login: Thu Sep 11 09:00:00 2026",
                "W${S}work${S}0${S}1${S}1${S}0${S}bash${S}bash",
            )
            .joinToString("\n")
    val snap = TmuxController.parseSnapshot(out, "ANOTERM_TTY_1")
    assertEquals(1, snap.windows.size)
  }

  @Test
  fun readsTheFormTmuxActuallyReturns() {
    // tmux は出力に含まれる非表示文字を `\\037` という 4 文字に置き換えて返すことがある。
    // 送ったのは 0x1F の 1 バイトでも、返りはこの形。ここを読めていなかったせいで
    // 一覧が「tmux なし」としか言えなかった。
    val e = "\\037"
    val out =
        listOf(
                "C${e}/dev/pts/3${e}work",
                "W${e}work${e}0${e}1${e}1${e}1789054933${e}claude${e}claude",
                "W${e}work${e}1${e}0${e}2${e}1789054000${e}bash${e}my notes",
            )
            .joinToString("\n")

    val snap = TmuxController.parseSnapshot(out, "ANOTERM_TTY_1")

    assertEquals(2, snap.windows.size)
    assertEquals("work", snap.attached)
    assertEquals("claude", snap.windows[0].command)
    assertEquals("my notes", snap.windows[1].name)
  }

  @Test
  fun readsNothingWhenTheSeparatorNeverArrives() {
    // 区切りが落ちて空白に化けた場合。1 件も取れないのが正しい挙動
    // （読み違えて別のウィンドウを選ばせるくらいなら、何も出さないほうがよい）。
    val out = "W work 0 1 1 0 bash bash"
    val snap = TmuxController.parseSnapshot(out, "ANOTERM_TTY_1")
    assertEquals(0, snap.windows.size)
    assertNull(snap.attached)
  }

  @Test
  fun fallsBackToTheOnlyClientWhenTheTtyIsUnknown() {
    // 環境変数を書く前に繋いだ接続。クライアントが 1 つならそれが自分。
    val out = listOf("-ANOTERM_TTY_1", "C${S}/dev/pts/9${S}solo").joinToString("\n")
    val snap = TmuxController.parseSnapshot(out, "ANOTERM_TTY_1")
    assertNull(snap.clientTty)
    assertEquals("solo", snap.attached)
  }
}
