package app.anoterm

import app.anoterm.terminal.ScreenScan
import app.anoterm.terminal.TapSpan
import app.anoterm.terminal.TapTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenScanTest {

  /** 画像として拾えたパスだけを返す補助。URL は別のテストで見る。 */
  private fun at(lines: List<String>, cols: Int, row: Int, col: Int) =
      (ScreenScan.targetAt(lines, cols, row, col) as? TapTarget.Image)?.path

  @Test
  fun findsAPathUnderTheFinger() {
    val lines = listOf("Wrote the chart to /tmp/chart.png", "")
    assertEquals("/tmp/chart.png", at(lines, 80, 0, 22))
    // 単語のどこを押しても同じものが返る。端を狙わせない。
    assertEquals("/tmp/chart.png", at(lines, 80, 0, 19))
    assertEquals("/tmp/chart.png", at(lines, 80, 0, 32))
  }

  @Test
  fun ignoresWhatIsNotAnImage() {
    val lines = listOf("edited /srv/w/Main.kt and /srv/w/notes.txt")
    assertNull(at(lines, 80, 0, 10))
    assertNull(at(lines, 80, 0, 30))
  }

  @Test
  fun dropsPunctuationThatCameWithTheSentence() {
    val lines = listOf("saved (/tmp/a.png), see it.")
    assertEquals("/tmp/a.png", at(lines, 80, 0, 8))
  }

  @Test
  fun acceptsRelativePathsAndSaysTheyNeedARoot() {
    // 押して開かないより、押せば開くほうがよい。基準は読む側が決める。
    assertTrue(ScreenScan.isImagePath("docs/shot.png"))
    assertTrue(ScreenScan.isRelative("docs/shot.png"))
    assertFalse(ScreenScan.isRelative("/docs/shot.png"))
    assertFalse(ScreenScan.isRelative("~/docs/shot.png"))
  }

  @Test
  fun leavesUrlsToTheBrowser() {
    val lines = listOf("  Local:   http://localhost:5173/ ready")
    val target = ScreenScan.targetAt(lines, 80, 0, 20)
    assertEquals(TapTarget.Url("http://localhost:5173/"), target)
    // 末尾が .png でも、URL なら画像として読みには行かない。
    assertFalse(ScreenScan.isImagePath("http://example.com/a.png"))
  }

  @Test
  fun refusesUrlsWithNonsensePorts() {
    assertFalse(ScreenScan.isUrl("http://host:abc/"))
    assertTrue(ScreenScan.isUrl("http://host:8080/"))
    assertFalse(ScreenScan.isUrl("ftp://host/a"))
  }

  @Test
  fun takesTheWholeQuotedNameSoSpacesSurvive() {
    // 空白入りの名前は、空白で語を切る限り絶対に拾えない。囲ってあるならそれが境界。
    val lines = listOf("wrote \"/tmp/my shot.png\" ok")
    assertEquals("/tmp/my shot.png", at(lines, 80, 0, 12))
  }

  @Test
  fun stripsTheFileScheme() {
    val lines = listOf("see file:///tmp/a.png now")
    assertEquals("/tmp/a.png", at(lines, 80, 0, 12))
  }

  @Test
  fun joinsAPathBrokenAcrossTheWrap() {
    // 幅 20 で折り返した状態。前の行が右端まで埋まっているので続きとみなす。
    val cols = 20
    val lines = listOf("saved /srv/site/work", "/pictures/shot.png ok")
    assertEquals("/srv/site/work/pictures/shot.png", at(lines, cols, 0, 15))
    assertEquals("/srv/site/work/pictures/shot.png", at(lines, cols, 1, 3))
  }

  @Test
  fun doesNotJoinLinesThatSimplyEnded() {
    // 前の行は右端まで届いていない = そこで改行された。繋いではいけない。
    val cols = 40
    val lines = listOf("saved /srv/w", "/other.png")
    assertNull(at(lines, cols, 0, 8))
    assertEquals("/other.png", at(lines, cols, 1, 3))
  }

  @Test
  fun marksEveryPathOnTheScreen() {
    val lines = listOf("a /tmp/1.png b", "/tmp/2.png")
    assertEquals(
        listOf(TapSpan(0, 2, 11), TapSpan(1, 0, 9)),
        ScreenScan.tapSpans(lines, 80),
    )
  }

  @Test
  fun quotesForTheShellWithoutLettingAnythingEscape() {
    assertEquals("'/tmp/a.png'", ScreenScan.shellWord("/tmp/a.png"))
    // 単引用符入りのファイル名でコマンドが割れない。
    assertEquals("'/tmp/it'\\''s.png'", ScreenScan.shellWord("/tmp/it's.png"))
    // ~ はシェルに展開させる必要があるので引用符の外に出す。
    assertEquals("\"\$HOME\"/'a.png'", ScreenScan.shellWord("~/a.png"))
  }
}

class RemoteImageSampleTest {
  @Test
  fun picksAPowerOfTwoThatFitsTheLimit() {
    // BitmapFactory は 2 の冪しか受け付けない。長辺が limit に収まる最小を選ぶ。
    assertEquals(1, app.anoterm.ssh.RemoteImage.sampleSizeFor(1000, 800, 2048))
    // 上限を下回るまで間引く。原寸だと 4000x3000 で 48MB になる。
    assertEquals(2, app.anoterm.ssh.RemoteImage.sampleSizeFor(4000, 3000, 2048))
    assertEquals(4, app.anoterm.ssh.RemoteImage.sampleSizeFor(8192, 3000, 2048))
    assertEquals(8, app.anoterm.ssh.RemoteImage.sampleSizeFor(16384, 100, 2048))
  }
}
