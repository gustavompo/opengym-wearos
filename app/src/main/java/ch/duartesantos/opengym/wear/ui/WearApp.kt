package ch.duartesantos.opengym.wear.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import ch.duartesantos.opengym.wear.data.FakeWorkoutRepository
import ch.duartesantos.opengym.wear.data.WorkoutRepository
import ch.duartesantos.opengym.wear.link.PhoneLinkSource
import ch.duartesantos.opengym.wear.link.PhoneLinkState
import ch.duartesantos.opengym.wear.link.emptyWorkoutMessage
import ch.duartesantos.opengym.wear.navigation.Destination
import ch.duartesantos.opengym.wear.navigation.WorkoutNavigation
import ch.duartesantos.opengym.wear.theme.OpenGymWearTheme
import ch.duartesantos.opengym.wear.ui.exercise.ExercisePage
import ch.duartesantos.opengym.wear.ui.rest.RestTimer
import ch.duartesantos.opengym.wear.ui.workout.WorkoutPage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The app root: one shared state holder (the repository), three screens and one navigation state
 * machine.
 *
 *  split the old derived destination in two. Before this, the screen was *derived* from the
 * projection alone (`destinationFor`), which cannot express "the user is looking at the list". Now
 * [WorkoutNavigation] holds the one piece of user state — the chosen screen — and [WorkoutNavigation.screen]
 * still lets the rest clock override it and still forces the exercise page when there is no workout.
 * The projection is mirrored into it on every frame, so the always-derived properties survive.
 *
 * The BACK gesture (swipe right, and the system back key) lives here, **above** the page, so a page's
 * own vertical drag is untouched. That is 's arbitration: the wheel consumes vertical drags
 * and this consumes horizontal ones, and neither sees the other's.
 *
 * when the repository is backed by a phone link ([PhoneLinkSource]), the no-workout screen says
 * "Phone not connected" instead of "No active workout" while the link is down. Nothing else about the
 * screens changes, and a repository with no link (the fake, the previews) renders exactly what it did
 * before.
 */
@Composable
fun WearApp(repository: WorkoutRepository, modifier: Modifier = Modifier) {
  val state by repository.observeWorkout().collectAsStateWithLifecycle()
  val linkSource = remember(repository) { repository as? PhoneLinkSource }
  val phoneLinkFlow = remember(linkSource) { linkSource?.observePhoneLink() }
  val link by (phoneLinkFlow ?: NoPhoneLink).collectAsStateWithLifecycle()
  val selection by repository.observeExerciseSelection().collectAsStateWithLifecycle()

  val navigation = remember(repository) { WorkoutNavigation() }
  val destination = navigation.screen(state)

  // Mirrored in an effect rather than written during composition: the state machine must not mutate
  // state while Compose is measuring. Both halves are needed — `onState` carries rule 5 and
  // the accepted half of rule 6, `onSelection` the abandoned half.
  LaunchedEffect(state) { navigation.onState(state) }
  LaunchedEffect(selection) { navigation.onSelection(selection) }

  val activity = LocalContext.current as? Activity
  // The system back key is the same action as the swipe; `back` answers whether
  // the app should finish, which is the system default from the list.
  BackHandler(enabled = true) { if (navigation.back()) activity?.finish() }

  OpenGymWearTheme {
    // A single AppScaffold wraps all destinations so the static TimeText stays put across the change
    // from one to the other.
    //
    // the clock is suppressed on the rest screen. `TimeText` paints an opaque rounded
    // ribbon behind the clock at the top of the face, and the rest ring is deliberately drawn
    // flush to the bezel, so that
    // ribbon covers the arc at 12 o'clock - 24 degrees of it, and in the final seconds the whole
    // remaining arc. The ring then reads as empty exactly when the countdown matters. The reference's
    // rest screen has no clock there either (its top row is the stat row, see
    //  section 8), so nothing is lost. The exercise page and
    // the workout page keep the scaffold's clock unchanged.
    AppScaffold(
      modifier = modifier,
      timeText = { if (destination != Destination.RestTimer) TimeText() },
    ) {
      Box(
        modifier =
          Modifier.fillMaxSize().pointerInput(Unit) {
            backSwipe { if (navigation.back()) activity?.finish() }
          }
      ) {
        when (destination) {
          Destination.ExercisePage ->
            ExercisePage(
              state = state,
              onCompleteSet = repository::completeSet,
              //  rule 4: `‹` on the first exercise is no longer dead — it opens the list.
              // The chevron is therefore enabled everywhere and this decides what it means.
              onPreviousExercise = {
                if (state.canGoPrevious) repository.previousExercise() else navigation.openWorkoutPage()
              },
              onNextExercise = repository::nextExercise,
              onAdjustWeight = repository::adjustWeight,
              onAdjustReps = repository::adjustReps,
              onSetWeight = repository::setWeight,
              onSetReps = repository::setReps,
              // the two lines above are agent B's wheel wiring — without them a drag previews
              // but commits nothing (measured by B).
              //
              // s `canGoPrevious` greyed the chevron out on the first exercise. Rule 4 overrides
              // that: the chevron has a job there now, so it is never disabled.
              canGoPrevious = true,
              canGoNext = state.canGoNext,
              emptyMessage = emptyWorkoutMessage(link),
            )
          Destination.WorkoutPage ->
            WorkoutPage(
              state = state,
              //  rule 6: the row tap either needs no command (the row is already current) or
              // sends exactly one `selectExercise`; either way the screen change is the state
              // machine's decision, made here and in `LaunchedEffect(state)`.
              onSelectRow = { index -> navigation.onRowTap(index, state)?.let(repository::selectExercise) },
              pendingExerciseIndex = navigation.pendingExerciseIndex,
              emptyMessage = emptyWorkoutMessage(link),
            )
          Destination.RestTimer ->
            RestTimer(
              state = state,
              onAdjustRest = repository::adjustRest,
              onSkipRest = repository::skipRest,
            )
        }
      }
    }
  }
}

/**
 *  rule 3's gesture: a rightward swipe is BACK.
 *
 * [detectHorizontalDragGestures] is the right primitive rather than a hand-rolled pointer loop: it
 * waits for **horizontal** slop before it reports anything and consumes only then, so a vertical drag
 * that starts on the exercise page's wheel cards is left entirely alone, and a tap never becomes a
 * swipe. This node sits above the page, so it only ever sees drags the page did not consume — which
 * is exactly the arbitration fixes.
 *
 * The distance is a fraction of the screen width and is recorded as a **judgement call**: no reference
 * measurement exists for a gesture, and the honest constraints on it are only that it is far enough
 * that a tap is not a swipe and short enough that a deliberate swipe is not rejected.
 */
private suspend fun PointerInputScope.backSwipe(onBack: () -> Unit) {
  val threshold = size.width * BACK_SWIPE_FRACTION
  var travelled = 0f
  detectHorizontalDragGestures(
    onDragStart = { travelled = 0f },
    onDragEnd = { if (travelled >= threshold) onBack() },
    onHorizontalDrag = { change, dragAmount ->
      change.consume()
      travelled += dragAmount
    },
  )
}

/** How far right a swipe must travel to be BACK: 18 % of the screen width. See [backSwipe]. */
private const val BACK_SWIPE_FRACTION = 0.18f

@Preview
@WearPreviewDevices
@Composable
private fun WearAppPreview() {
  WearApp(repository = remember { FakeWorkoutRepository() })
}

/**
 * The link state for a repository that has no phone link. `null` rather than a "connected" value, so
 * [emptyWorkoutMessage] can tell "there is no link" apart from "the link is up and idle".
 */
private val NoPhoneLink: StateFlow<PhoneLinkState?> = MutableStateFlow(null)
