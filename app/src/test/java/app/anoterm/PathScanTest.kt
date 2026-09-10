package app.anoterm

import app.anoterm.terminal.PathScan
import app.anoterm.terminal.PathSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PathScanTest {

  private fun at(lines: List<String>, cols: Int, row: Int, col: Int) =
      PathScan.imagePathAt(lines, cols, row, col)

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
  fun refusesRelativePaths() {
    // 別セッションで読むので、相対パスの基準が違う。開けるふりをしないこと。
    assertFalse(PathScan.isImagePath("docs/shot.png"))
    assertTrue(PathScan.isImagePath("/docs/shot.png"))
    assertTrue(PathScan.isImagePath("~/docs/shot.png"))
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
        listOf(PathSpan(0, 2, 11), PathSpan(1, 0, 9)),
        PathScan.imagePathSpans(lines, 80),
    )
  }

  @Test
  fun quotesForTheShellWithoutLettingAnythingEscape() {
    assertEquals("'/tmp/a.png'", PathScan.shellWord("/tmp/a.png"))
    // 単引用符入りのファイル名でコマンドが割れない。
    assertEquals("'/tmp/it'\\''s.png'", PathScan.shellWord("/tmp/it's.png"))
    // ~ はシェルに展開させる必要があるので引用符の外に出す。
    assertEquals("\"\$HOME\"/'a.png'", PathScan.shellWord("~/a.png"))
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
