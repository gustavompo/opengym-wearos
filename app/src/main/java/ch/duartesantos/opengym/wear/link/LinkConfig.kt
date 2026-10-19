package ch.duartesantos.opengym.wear.link

import android.content.Context
import android.util.Log
import ch.duartesantos.opengym.wear.data.FakeWorkoutRepository
import ch.duartesantos.opengym.wear.data.TimeSource
import ch.duartesantos.opengym.wear.data.WorkoutRepository

/**
 * Which transport the watch should use, and where the TCP one should look.
 *
 * **A normally-launched app uses the Data Layer** ([TransportKind.DATALAYER]). It is an intent extra
 * rather than a `BuildConfig` field so the same APK can be pointed somewhere else without a rebuild —
 * one `adb shell am start` switches the emulator dev loop over:
 *
 * ```
 * adb shell am start -n ch.duartesantos.opengym.test/ch.duartesantos.opengym.wear.MainActivity \
 *   --es transport tcp --es tcpHost 10.0.2.2 --ei tcpPort 8765
 * ```
 *
 * `--es transport fake` is what the instrumented suite and anything else that must run with no phone
 * uses. There is deliberately no silent fallback from [TransportKind.DATALAYER] to
 * [TransportKind.FAKE]: a watch rendering a plausible-looking fake workout it never received is
 * indistinguishable from a live one, which is exactly the confusion `12-datalayer-decisions.md`
 * exists to prevent. With no phone reachable the Data Layer transport reports `Disconnected` and the
 * screen says so.
 */
data class LinkConfig(
  val transport: TransportKind = TransportKind.DEFAULT,
  val tcpHost: String = TcpRelayTransport.EMULATOR_HOST,
  val tcpPort: Int = TcpRelayTransport.DEFAULT_PORT,
) {
  companion object {
    const val EXTRA_TRANSPORT: String = "transport"
    const val EXTRA_TCP_HOST: String = "tcpHost"
    const val EXTRA_TCP_PORT: String = "tcpPort"

    /** Pure so it is unit-testable without an `Intent` or `Bundle`. */
    fun fromArgs(transport: String?, tcpHost: String?, tcpPort: Int?): LinkConfig =
      LinkConfig(
        transport = TransportKind.fromWire(transport),
        tcpHost = tcpHost?.takeIf { it.isNotBlank() } ?: TcpRelayTransport.EMULATOR_HOST,
        tcpPort = tcpPort?.takeIf { it in 1..65_535 } ?: TcpRelayTransport.DEFAULT_PORT,
      )
  }
}

enum class TransportKind(val wire: String) {
  /**
   * In-memory fake. No phone involved, and therefore never what a plain launch gets — it is the
   * explicit `--es transport fake` the instrumented suite and the previews use.
   */
  FAKE("fake"),

  /** `play-services-wearable`. The production path, and the default. */
  DATALAYER("datalayer"),

  /** Newline-delimited JSON over TCP. Emulator development and CI only. */
  TCP("tcp"),
  ;

  companion object {
    /** What a normally-launched app uses: prefer the Data Layer. */
    val DEFAULT: TransportKind = DATALAYER

    /** The override a user actually asked for, or null when there was none. */
    fun fromOverride(value: String?): TransportKind? =
      entries.firstOrNull { it.wire.equals(value?.trim(), ignoreCase = true) }

    /**
     * Total: an absent or unrecognised string means "no override", i.e. [DEFAULT]. Unknown values
     * are not an error — a stale `adb am start` script from the TCP era should not turn into a
     * crashed watch. It does mean a typo silently gets the Data Layer, which is the production path
     * and the one that says "phone not connected" rather than inventing anything.
     */
    fun fromWire(value: String?): TransportKind = fromOverride(value) ?: DEFAULT
  }
}

/**
 * Builds the repository the activity renders, from a [LinkConfig].
 *
 * Takes a [Context] because the Data Layer client is built from one. That is the only reason; the
 * fake and TCP paths ignore it.
 */
object WorkoutRepositories {
  private const val TAG = "WorkoutRepositories"

  fun create(
    context: Context,
    config: LinkConfig,
    timeSource: TimeSource = TimeSource.system(),
  ): WorkoutRepository =
    when (config.transport) {
      TransportKind.DATALAYER -> {
        Log.i(TAG, "transport=datalayer (Wearable Data Layer, ${DataLayerContract.MESSAGE_PATH})")
        TransportWorkoutRepository(
          transport = DataLayerTransport(client = WearableDataLayerClient(context.applicationContext)),
          timeSource = timeSource,
        )
      }
      TransportKind.TCP -> {
        Log.i(TAG, "transport=tcp ${config.tcpHost}:${config.tcpPort}")
        TransportWorkoutRepository(
          transport = TcpRelayTransport(host = config.tcpHost, port = config.tcpPort),
          timeSource = timeSource,
        )
      }
      TransportKind.FAKE -> {
        Log.i(TAG, "transport=fake (explicitly requested; no phone involved)")
        FakeWorkoutRepository(timeSource = timeSource)
      }
    }
}
