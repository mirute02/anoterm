package app.anoterm

import app.anoterm.terminal.ScreenScan
import app.anoterm.terminal.TapTarget
import org.junit.Assert.assertEquals
import org.junit.Test

/** Claude Code が実際に画面へ出す形のまま、拾えるかどうかを見る。 */
class TapTargetRealWorldTest {

  private fun hit(line: String, col: Int, cols: Int = 80) =
      ScreenScan.targetAt(listOf(line.padEnd(cols)), cols, 0, col)

  @Test
  fun findsThemInSentences() {
    val cases =
        listOf(
            "Wrote /srv/site/work/android/sshclient/CHANGELOG.md" to 30,
            "  Update(README.md)" to 12,
            "saved the chart to /tmp/chart.png" to 24,
            "> /srv/w/shot.jpg" to 12,
            "  Read /srv/w/docs/design.md (120 lines)" to 20,
        )
    for ((line, col) in cases) {
      val t = hit(line, col)
      println(line + "  ->  " + t)
      assert(t != null) { "拾えなかった: " + line }
    }
  }

  @Test
  fun tellsImagesFromDocuments() {
    assertEquals(
        TapTarget.Text("/srv/w/a.md"),
        hit("see /srv/w/a.md now", 10),
    )
    assertEquals(
        TapTarget.Image("/srv/w/a.png"),
        hit("see /srv/w/a.png now", 10),
    )
  }
}
