package ch.duartesantos.opengym.wear.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TitleCaseTest {

  @Test
  fun raisesTheFirstLetterOfEveryWord() {
    assertEquals("Cable Lat Pulldown", "cable lat pulldown".titleCased())
    assertEquals("Lat Pulldown", "Lat Pulldown".titleCased())
  }

  @Test
  fun leavesTheRestOfEachWordAloneSoAcronymsSurvive() {
    assertEquals("Ez-Bar Curl", "ez-Bar curl".titleCased())
    assertEquals("EZ-Bar Curl", "EZ-Bar Curl".titleCased())
    assertEquals("RDL", "RDL".titleCased())
  }

  @Test
  fun hyphensSlashsAndParenthesesStartNewWordsButApostrophesDoNot() {
    assertEquals("Pull-Up", "pull-up".titleCased())
    assertEquals("T-Bar Row", "t-bar row".titleCased())
    assertEquals("Farmer's Walk", "farmer's walk".titleCased())
    assertEquals("Bench Press (Dumbbell)", "bench press (dumbbell)".titleCased())
    assertEquals("Cable Crossover 21s", "cable crossover 21s".titleCased())
  }

  @Test
  fun anEmptyOrBlankNameIsUntouched() {
    assertEquals("", "".titleCased())
    assertEquals("  ", "  ".titleCased())
  }
}
