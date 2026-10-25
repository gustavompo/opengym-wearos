package ch.duartesantos.opengym.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.wear.compose.foundation.LocalAmbientModeManager
import androidx.wear.compose.foundation.rememberAmbientModeManager
import ch.duartesantos.opengym.wear.data.WorkoutRepository
import ch.duartesantos.opengym.wear.link.LinkConfig
import ch.duartesantos.opengym.wear.link.TransportWorkoutRepository
import ch.duartesantos.opengym.wear.link.WorkoutRepositories
import ch.duartesantos.opengym.wear.ui.WearApp

/**
 * The app's single activity.
 *
 * The repository is chosen by [LinkConfig], which reads intent extras. With no extras it is the
 * **Wearable Data Layer** — the production path, and the only one that works on a real watch with no
 * configuration. `--es transport fake` is the explicit override for the instrumented suite and the
 * previews, which must run with no phone; `--es transport tcp` is the emulator dev loop, which is the
 * only end-to-end path proven on this machine:
 *
 * ```
 * adb shell am start -n ch.duartesantos.opengym.test/ch.duartesantos.opengym.wear.MainActivity \
 *   --es transport tcp --es tcpHost 10.0.2.2 --ei tcpPort 8765
 * ```
 *
 * The screens are unchanged either way; they render [WorkoutRepository] and nothing else (plus, for a
 * link-backed repository, whether the phone is reachable — see `link/PhoneLink.kt`).
 */
class MainActivity : ComponentActivity() {

  private lateinit var repository: WorkoutRepository

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val config =
      LinkConfig.fromArgs(
        transport = intent?.getStringExtra(LinkConfig.EXTRA_TRANSPORT),
        tcpHost = intent?.getStringExtra(LinkConfig.EXTRA_TCP_HOST),
        tcpPort = intent?.getIntExtra(LinkConfig.EXTRA_TCP_PORT, 0)?.takeIf { it > 0 },
      )
    repository = WorkoutRepositories.create(applicationContext, config)

    setContent {
      val ambientModeManager = rememberAmbientModeManager()
      CompositionLocalProvider(LocalAmbientModeManager provides ambientModeManager) {
        WearApp(repository = repository)
      }
    }
  }

  override fun onResume() {
    super.onResume()
    // The screen is the first thing the user sees after a wrist raise; publish the deadline's
    // current value before that frame instead of waiting for the next tick.
    repository.refresh()
  }

  override fun onDestroy() {
    // Only the transport-backed repository owns anything that needs releasing.
    (repository as? TransportWorkoutRepository)?.close()
    super.onDestroy()
  }
}
