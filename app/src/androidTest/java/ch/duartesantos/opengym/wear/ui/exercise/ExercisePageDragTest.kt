package ch.duartesantos.opengym.wear.ui.exercise

import androidx.activity.ComponentActivity
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.duartesantos.opengym.wear.data.ActiveWorkout
import ch.duartesantos.opengym.wear.data.ExerciseSelection
import ch.duartesantos.opengym.wear.data.FakeWorkoutRepository
import ch.duartesantos.opengym.wear.data.TimeSource
import ch.duartesantos.opengym.wear.data.WorkoutRepository
import ch.duartesantos.opengym.wear.data.WorkoutState
import ch.duartesantos.opengym.wear.theme.OpenGymWearTheme
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M7.2: the drag wheel, on the device, through the real touch pipeline.
 *
 * These are real `MotionEvent`s dispatched at the card (Compose's input injection), so they exercise
 * the same `detectVerticalDragGestures` the finger does — not a direct call to the wheel's
 * arithmetic, which the JVM tests in `ExercisePageWheelTest` cover.
 *
 * What each test is for, and why it is not redundant:
 *  - a vertical drag moves the value **and** commits exactly one absolute command;
 *  - a drag that returns to its origin commits nothing at all;
 *  - a **horizontal** drag on a card changes nothing — the half of plan §2.5 this feature owns;
 *  - a horizontal drag starting on a card is still seen by a horizontal detector **above** the page,
 *    which is the mechanism the BACK gesture hangs off (§2.5: "the back gesture is horizontal and
 *    lives above the page, so it still sees drags the page did not consume");
 *  - a `state` that arrives mid-drag does not move the digits, and the projection wins again when
 *    the finger lifts (§2.6).
 */
@RunWith(AndroidJUnit4::class)
class ExercisePageDragTest {

  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  /** Pixels a horizontal detector above the page was given, for the §2.5 test. */
  private var horizontalDragPx = 0f

  private fun show(withParentHorizontalDetector: Boolean = false): RecordingRepository {
    val repository = RecordingRepository()
    rule.setContent {
      OpenGymWearTheme {
        val state by repository.observeWorkout().collectAsState()
        // Stands in for the page above this one, where the swipe-right BACK gesture will live.
        // Nothing on the card may consume a horizontal drag before this sees it (plan §2.5).
        val pageModifier =
          if (withParentHorizontalDetector) {
            Modifier.fillMaxSize().pointerInput(Unit) {
              detectHorizontalDragGestures { _, dragAmount -> horizontalDragPx += dragAmount }
            }
          } else {
            Modifier
          }
        Box(pageModifier) {
          ExercisePage(
            state = state,
            onCompleteSet = repository::completeSet,
            onPreviousExercise = repository::previousExercise,
            onNextExercise = repository::nextExercise,
            onAdjustWeight = repository::adjustWeight,
            onAdjustReps = repository::adjustReps,
            onSetWeight = repository::setWeight,
            onSetReps = repository::setReps,
          )
        }
      }
    }
    return repository
  }

  @Test
  fun aVerticalDragOnTheWeightCardMovesTheValueAndCommitsOneAbsoluteCommand() {
    val repository = show()
    rule.onNodeWithText("50.0").assertIsDisplayed()

    // Four rows of travel, which is four 0.5 kg steps: 50.0 -> 52.0.
    dragVertically(EXERCISE_PAGE_WEIGHT_TAG, rows = 4f)

    assertEquals(52.0, repository.delegate.observeWorkout().value.weightKg, 0.0)
    assertEquals(listOf("setWeight(52.0)"), repository.commands)
    rule.onNodeWithText("52.0").assertIsDisplayed()
    rule.onNodeWithText("51.5").assertIsDisplayed() // the neighbour follows the new value
    rule.onNodeWithText("52.5").assertIsDisplayed()
  }

  @Test
  fun aVerticalDragOnTheRepsCardMovesTheReps() {
    val repository = show()

    dragVertically(EXERCISE_PAGE_REPS_TAG, rows = 2f)

    assertEquals(12, repository.delegate.observeWorkout().value.reps)
    assertEquals(listOf("setReps(12)"), repository.commands)
    rule.onNodeWithText("12").assertIsDisplayed()
    // The weight column is untouched by a drag on the other card.
    assertEquals(50.0, repository.delegate.observeWorkout().value.weightKg, 0.0)
  }

  @Test
  fun aDragThatReturnsToItsStartCommitsNothing() {
    val repository = show()
    val rowPx = rowHeightPx(EXERCISE_PAGE_WEIGHT_TAG)

    rule.onNodeWithTag(EXERCISE_PAGE_WEIGHT_TAG).performTouchInput {
      down(center)
      repeat(3) {
        moveBy(Offset(0f, -2f * rowPx / 3f))
        advanceEventTime(16L)
      }
      repeat(3) {
        moveBy(Offset(0f, +2f * rowPx / 3f))
        advanceEventTime(16L)
      }
      up()
    }
    rule.waitForIdle()

    assertEquals(emptyList<String>(), repository.commands)
    assertEquals(50.0, repository.delegate.observeWorkout().value.weightKg, 0.0)
    rule.onNodeWithText("50.0").assertIsDisplayed()
  }

  @Test
  fun aHorizontalDragOnACardChangesNothing() {
    val repository = show()
    rule.onNodeWithText("50.0").assertIsDisplayed()

    dragHorizontally(EXERCISE_PAGE_WEIGHT_TAG, dx = 220f)

    assertEquals(emptyList<String>(), repository.commands)
    assertEquals(50.0, repository.delegate.observeWorkout().value.weightKg, 0.0)
    rule.onNodeWithText("50.0").assertIsDisplayed()
  }

  @Test
  fun aHorizontalDragStartingOnACardReachesThePageAboveIt() {
    val repository = show(withParentHorizontalDetector = true)
    rule.onNodeWithText("50.0").assertIsDisplayed()

    dragHorizontally(EXERCISE_PAGE_WEIGHT_TAG, dx = 220f)

    // The card did not eat it, and the detector above the page saw the whole travel.
    assertTrue(
      "a horizontal drag on the card must reach the page above it, got $horizontalDragPx px",
      horizontalDragPx >= 200f,
    )
    assertEquals(emptyList<String>(), repository.commands)
    assertEquals(50.0, repository.delegate.observeWorkout().value.weightKg, 0.0)
  }

  @Test
  fun anIncomingProjectionDoesNotMoveTheDigitsWhileTheFingerIsDown() {
    val repository = show()
    val rowPx = rowHeightPx(EXERCISE_PAGE_WEIGHT_TAG)
    val card = rule.onNodeWithTag(EXERCISE_PAGE_WEIGHT_TAG)

    card.performTouchInput { down(center) }
    card.performTouchInput {
      moveBy(Offset(0f, -2f * rowPx))
      advanceEventTime(16L)
    }
    rule.waitForIdle()
    // Two rows up: the preview is 51.0 while the finger is still down.
    rule.onNodeWithText("51.0").assertIsDisplayed()

    // The phone pushes a different weight mid-drag. The dragged column must ignore it (§2.6).
    repository.delegate.adjustWeight(+7.0)
    rule.waitForIdle()
    // The projection really did move — the state is 57.0 — and the dragged column is still showing
    // its own preview, so this is the wheel ignoring the projection and not an absent update.
    assertEquals(57.0, repository.delegate.observeWorkout().value.weightKg, 0.0)
    rule.onNodeWithText("51.0").assertIsDisplayed()
    rule.onAllNodesWithText("57.0").assertCountEquals(0)

    card.performTouchInput { up() }
    rule.waitForIdle()

    // One command, computed from the base the finger started on (50.0 + 2 x 0.5), not from the
    // 57.0 that arrived mid-drag: the projection did not re-base the wheel (§2.6).
    assertEquals(listOf("setWeight(51.0)"), repository.commands)
    // The preview is gone, so the projection renders again. Here the fake has just applied the
    // commit, so the projection is the committed 51.0; against a transport-backed repository this
    // is the value the phone echoes back.
    assertEquals(51.0, repository.delegate.observeWorkout().value.weightKg, 0.0)
    rule.onNodeWithText("51.0").assertIsDisplayed()
  }

  @Test
  fun everyCardStillCarriesItsSemanticsForTheCrown() {
    show()

    // The tap-to-select-the-crown-target behaviour and the tags `ExercisePageTest` depends on.
    rule.onNodeWithTag(EXERCISE_PAGE_WEIGHT_TAG).assertIsDisplayed()
    rule.onNodeWithTag(EXERCISE_PAGE_REPS_TAG).assertIsDisplayed()
    rule.onNodeWithTag(EXERCISE_PAGE_PREV_TAG).assertIsDisplayed()
    rule.onNodeWithTag(EXERCISE_PAGE_COMPLETE_TAG).assertIsDisplayed()
    rule.onNodeWithTag(EXERCISE_PAGE_NEXT_TAG).assertIsDisplayed()
    rule.onNodeWithTag(EXERCISE_PAGE_TAG).assertIsDisplayed()
  }

  // --- helpers ---------------------------------------------------------------------------------

  /**
   * The card's row height in px, measured from the node's own bounds and not assumed: the wheel's
   * row is `cardHeight / 3` (plan §2.6), so this is the same number the implementation divides by.
   */
  private fun rowHeightPx(tag: String): Float =
    rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.height / 3f

  /** A vertical drag of [rows] card rows over the node tagged [tag], as four motion events. */
  private fun dragVertically(tag: String, rows: Float) {
    val rowPx = rowHeightPx(tag)
    rule.onNodeWithTag(tag).performTouchInput {
      down(center)
      repeat(4) {
        moveBy(Offset(0f, -rows * rowPx / 4f))
        advanceEventTime(16L)
      }
      up()
    }
    rule.waitForIdle()
  }

  /** A horizontal drag of [dx] px over the node tagged [tag], as four motion events. */
  private fun dragHorizontally(tag: String, dx: Float) {
    rule.onNodeWithTag(tag).performTouchInput {
      down(center)
      repeat(4) {
        moveBy(Offset(dx / 4f, 0f))
        advanceEventTime(16L)
      }
      up()
    }
    rule.waitForIdle()
  }
}

/**
 * A [WorkoutRepository] that delegates to a real [FakeWorkoutRepository] and records every command
 * by name and value.
 *
 * Recording is the point: a drag's *outcome* (the weight changed) is also what a stream of
 * `adjustWeight` deltas would produce against the fake, so the test has to assert the **shape** of
 * the commit — one absolute `setWeight`, and nothing else (plan §2.3, §2.6).
 */
private class RecordingRepository(
  initialWorkout: ActiveWorkout = ActiveWorkout.FullBodyA,
) : WorkoutRepository {

  /** Frozen so only a command can move the state. */
  val delegate =
    FakeWorkoutRepository(
      initialWorkout = initialWorkout,
      timeSource = TimeSource { 1_791_665_134_901L },
    )

  val commands = mutableListOf<String>()

  override fun observeWorkout(): StateFlow<WorkoutState> = delegate.observeWorkout()

  override fun completeSet() {
    commands += "completeSet"
    delegate.completeSet()
  }

  override fun nextExercise() {
    commands += "nextExercise"
    delegate.nextExercise()
  }

  override fun previousExercise() {
    commands += "previousExercise"
    delegate.previousExercise()
  }

  // M7.4 (merge): agent A extended `WorkoutRepository` with the workout page's tap while this file
  // was being written in a separate tree, so these two members exist only here. Delegated exactly
  // like the rest, and recorded, so a test that ever exercises the wheel *and* the list still sees
  // the truth rather than a hole.
  override fun selectExercise(index: Int) {
    commands += "selectExercise($index)"
    delegate.selectExercise(index)
  }

  override fun observeExerciseSelection(): StateFlow<ExerciseSelection> =
    delegate.observeExerciseSelection()

  override fun adjustWeight(deltaKg: Double) {
    commands += "adjustWeight($deltaKg)"
    delegate.adjustWeight(deltaKg)
  }

  override fun adjustReps(delta: Int) {
    commands += "adjustReps($delta)"
    delegate.adjustReps(delta)
  }

  override fun setWeight(weightKg: Double) {
    commands += "setWeight($weightKg)"
    delegate.setWeight(weightKg)
  }

  override fun setReps(reps: Int) {
    commands += "setReps($reps)"
    delegate.setReps(reps)
  }

  override fun adjustRest(deltaSeconds: Int) {
    commands += "adjustRest($deltaSeconds)"
    delegate.adjustRest(deltaSeconds)
  }

  override fun skipRest() {
    commands += "skipRest"
    delegate.skipRest()
  }
}