package ch.duartesantos.opengym.wear.ui.exercise

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.duartesantos.opengym.wear.data.ActiveWorkout
import ch.duartesantos.opengym.wear.data.FakeWorkoutRepository
import ch.duartesantos.opengym.wear.data.TimeSource
import ch.duartesantos.opengym.wear.theme.OpenGymWearTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  evidence: the exercise page, rendered against the real [FakeWorkoutRepository] and driven
 * through it. `WearApp` is not involved — the screen is mounted directly, so a failure here is a
 * failure of this screen and not of navigation.
 *
 * The repository's clock is frozen ([TimeSource] returning a constant) so nothing but a command can
 * move the state: no sleeps, no virtual time, no flakes.
 */
@RunWith(AndroidJUnit4::class)
class ExercisePageTest {

  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  /** Every command the screen issued, in order, so a button cannot silently do the wrong thing. */
  private val calls = mutableListOf<String>()

  private fun show(
    workout: ActiveWorkout = ActiveWorkout.FullBodyA,
    canGoPrevious: Boolean = true,
    canGoNext: Boolean = true,
  ): FakeWorkoutRepository {
    val repository = FakeWorkoutRepository(initialWorkout = workout, timeSource = FROZEN_CLOCK)
    rule.setContent {
      OpenGymWearTheme {
        val state by repository.observeWorkout().collectAsState()
        ExercisePage(
          state = state,
          onCompleteSet = {
            calls += "completeSet"
            repository.completeSet()
          },
          onPreviousExercise = {
            calls += "previousExercise"
            repository.previousExercise()
          },
          onNextExercise = {
            calls += "nextExercise"
            repository.nextExercise()
          },
          onAdjustWeight = {
            calls += "adjustWeight($it)"
            repository.adjustWeight(it)
          },
          onAdjustReps = {
            calls += "adjustReps($it)"
            repository.adjustReps(it)
          },
          canGoPrevious = canGoPrevious,
          canGoNext = canGoNext,
        )
      }
    }
    return repository
  }

  @Test
  fun rendersEveryStringTheReferenceShows() {
    show()

    // Title, counter, selector labels, selected values, the neighbours above and below each of
    // them, and the previous-set hint.
    rule.onNodeWithText("Lat Pulldown").assertIsDisplayed()
    rule.onNodeWithText("Set 1/4").assertIsDisplayed()
    rule.onNodeWithText("kg").assertIsDisplayed()
    rule.onNodeWithText("reps").assertIsDisplayed()
    rule.onNodeWithText("50.0").assertIsDisplayed()
    rule.onNodeWithText("10").assertIsDisplayed()
    rule.onNodeWithText("49.5").assertIsDisplayed()
    rule.onNodeWithText("50.5").assertIsDisplayed()
    rule.onNodeWithText("9").assertIsDisplayed()
    rule.onNodeWithText("11").assertIsDisplayed()
    rule.onNodeWithText("prev. 50.0 kg x 10").assertIsDisplayed()

    // The reference's heart-rate and calorie stats must NOT be faked: openGym has no source for
    // them and WorkoutState has no field for them.
    rule.onAllNodesWithText("102").assertCountEquals(0)
    rule.onAllNodesWithText("24").assertCountEquals(0)
  }

  @Test
  fun thePreviousSetHintDisappearsWhenThereIsNoPreviousSet() {
    // No set done yet in this session, and no history for the exercise either.
    show(ActiveWorkout.FullBodyA.copy(previousSession = emptyMap()))

    rule.onAllNodesWithText("prev.", substring = true).assertCountEquals(0)
    rule.onAllNodesWithText("null").assertCountEquals(0)
    // The rest of the page is unaffected.
    rule.onNodeWithText("50.0").assertIsDisplayed()
    rule.onNodeWithText("Set 1/4").assertIsDisplayed()
  }

  @Test
  fun theCheckButtonCompletesTheSetAndAdvancesTheCounter() {
    show()

    rule.onNodeWithTag(EXERCISE_PAGE_COMPLETE_TAG).performClick()

    assertEquals(listOf("completeSet"), calls)
    rule.onNodeWithText("Set 2/4").assertIsDisplayed()
    // The previous set is now this session's first set, so the hint follows it rather than history.
    rule.onNodeWithText("prev. 50.0 kg x 10").assertIsDisplayed()
  }

  @Test
  fun theChevronsMoveBetweenExercises() {
    val repository = show()

    rule.onNodeWithTag(EXERCISE_PAGE_NEXT_TAG).performClick()
    assertEquals("Dumbbell Row", repository.observeWorkout().value.exerciseName)
    rule.onNodeWithText("Set 1/3").assertIsDisplayed()
    rule.onNodeWithText("10.0").assertIsDisplayed()

    rule.onNodeWithTag(EXERCISE_PAGE_PREV_TAG).performClick()
    assertEquals("Lat Pulldown", repository.observeWorkout().value.exerciseName)
    assertEquals(listOf("nextExercise", "previousExercise"), calls)
  }

  @Test
  fun theChevronsCanBeDisabledAtTheEndsOfTheWorkout() {
    show(canGoPrevious = false, canGoNext = false)

    rule.onNodeWithTag(EXERCISE_PAGE_PREV_TAG).assertIsNotEnabled()
    rule.onNodeWithTag(EXERCISE_PAGE_NEXT_TAG).assertIsNotEnabled()
    // Disabling the chevrons must not disable the primary action.
    rule.onNodeWithTag(EXERCISE_PAGE_COMPLETE_TAG).performClick()
    assertEquals(listOf("completeSet"), calls)
  }

  @Test
  fun theWeightSpinnerClampsAndBlanksItsNeighbourAtTheEnds() {
    val repository = show()

    repository.adjustWeight(-1_000.0)
    rule.onNodeWithText("0.0").assertIsDisplayed()
    rule.onNodeWithText("0.5").assertIsDisplayed()
    rule.onAllNodesWithText("-0.5").assertCountEquals(0)

    repository.adjustWeight(+1_000.0)
    rule.onNodeWithText("500.0").assertIsDisplayed()
    rule.onNodeWithText("499.5").assertIsDisplayed()
    rule.onAllNodesWithText("500.5").assertCountEquals(0)
  }

  @Test
  fun theRepsSpinnerClampsAndBlanksItsNeighbourAtTheEnds() {
    val repository = show()

    repository.adjustReps(-1_000)
    rule.onNodeWithText("1").assertIsDisplayed()
    rule.onNodeWithText("2").assertIsDisplayed()
    rule.onAllNodesWithText("0").assertCountEquals(0)

    repository.adjustReps(+1_000)
    rule.onNodeWithText("99").assertIsDisplayed()
    rule.onNodeWithText("98").assertIsDisplayed()
    rule.onAllNodesWithText("100").assertCountEquals(0)
  }

  @Test
  fun bothSpinnersStepByTheirOwnAmounts() {
    val repository = show()

    repository.adjustWeight(+0.5)
    rule.onNodeWithText("50.5").assertIsDisplayed()

    repository.adjustReps(+1)
    rule.onNodeWithText("11").assertIsDisplayed()
  }

  /**
   * the `Set n/N` counter lives ON the label row now — its centre is between the two labels'
   * centres, its row overlaps theirs, and it sits above the spinner cards and below the title. This
   * is the geometry the user asked for ("the Set X/Y can go down and be on the same level as the
   * kg/reps labels"); the pixel measurement is the visual proof, this pins the
   * structure.
   */
  @Test
  fun theSetCounterSitsBetweenTheLabelsOnTheirRow() {
    show()

    val kg = rule.onNodeWithText("kg").fetchSemanticsNode().boundsInRoot
    val reps = rule.onNodeWithText("reps").fetchSemanticsNode().boundsInRoot
    val counter = rule.onNodeWithTag(EXERCISE_PAGE_COUNTER_TAG).fetchSemanticsNode().boundsInRoot
    val title = rule.onNodeWithText("Lat Pulldown").fetchSemanticsNode().boundsInRoot
    val card = rule.onNodeWithTag(EXERCISE_PAGE_WEIGHT_TAG).fetchSemanticsNode().boundsInRoot

    // Counter horizontally centred between the labels.
    assertTrue("counter centre %s not between labels".format(counter.center.x), counter.center.x > kg.right)
    assertTrue("counter centre %s not between labels".format(counter.center.x), counter.center.x < reps.left)
    // Labels and counter on ONE row: the counter's centre falls inside the labels' vertical
    // overlap band.
    val bandTop = Math.max(kg.top, reps.top)
    val bandBottom = Math.min(kg.bottom, reps.bottom)
    assertTrue("counter centre y %s outside label band %s..%s".format(counter.center.y, bandTop, bandBottom), counter.center.y >= bandTop)
    assertTrue(counter.center.y <= bandBottom)
    // Still in vertical order: title, then the shared row, then the cards.
    assertTrue(title.bottom <= counter.top)
    assertTrue(counter.bottom <= card.top)
    // The labels stayed on their cards' centres (the row that was removed did not move them).
    val repCard = rule.onNodeWithTag(EXERCISE_PAGE_REPS_TAG).fetchSemanticsNode().boundsInRoot
    assertEquals(kg.center.x, card.center.x, 4f)
    assertEquals(reps.center.x, repCard.center.x, 4f)
  }

  /**
   * a real 39-character name renders as a two-line title (cap 14, chord-capped) — the fix for
   * the reported clipping. The semantics still carry the full string; the *visual* proof that none
   * of it is cut by the bezel is the screencap measurement, which this test
   * cannot see. What it pins is that the title actually takes two lines and stays above the label
   * row.
   */
  @Test
  fun aLongExerciseNameTakesTwoTitleLinesAndStaysAboveTheLabelRow() {
    val workout = ActiveWorkout.FullBodyA.copy(
      exercises = listOf(
        ActiveWorkout.FullBodyA.exercises[0].copy(name = "cable lat pulldown full range of motion"),
      ),
    )
    show(workout)

    // The projection title-cases every exercise name (data/TitleCase.kt), so the page renders it
    // capitalised while the semantics still carry the same 39 characters — two lines' worth.
    rule.onNodeWithText("Cable Lat Pulldown Full Range Of Motion").assertIsDisplayed()
    val title = rule.onNodeWithText("Cable Lat Pulldown Full Range Of Motion").fetchSemanticsNode().boundsInRoot
    val kg = rule.onNodeWithText("kg").fetchSemanticsNode().boundsInRoot
    // Two lines of cap-14 title: ~36 dp = 72 px at 320 dpi; a single line would be ~36 px.
    assertTrue("title height %s".format(title.height), title.height >= 64f)
    assertTrue("title height %s".format(title.height), title.height < 100f)
    assertTrue("title %s not above labels %s".format(title.bottom, kg.top), title.bottom <= kg.top)
  }
}

/** Frozen so that only a command can change the rendered state. */
private val FROZEN_CLOCK: TimeSource = TimeSource { 1_791_665_134_901L }
