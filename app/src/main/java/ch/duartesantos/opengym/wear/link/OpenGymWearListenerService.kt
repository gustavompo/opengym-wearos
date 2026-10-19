package ch.duartesantos.opengym.wear.link

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * The watch's **only** receive path for the Data Layer, for both carriers.
 *
 * This exists for the reason the Data Layer is used at all: a manifest-registered Android component
 * is the one thing the system will unfreeze the app's process to deliver to. `MessageClient`
 * listeners registered from an activity only fire while the process happens to be alive, so a
 * `state` pushed by the phone after the watch app was swiped away would be lost — and the watch would
 * sit there showing nothing until the user opened it.
 *
 * **Both carriers, because the phone uses both.** `hello`/`ping`/`command`/`ack`/`pong` travel as
 * messages, but `state` — the one frame that carries the workout — travels as a `DataItem` *and* as a
 * message. Declaring `MESSAGE_RECEIVED` alone is therefore a
 * receiver for every frame except the one that matters: the handshake completes, the node is
 * discovered, the link reads `Connected`, and the payload is dropped on the doorstep. That was the
 * defect found on real hardware, and the system
 * names it at runtime as `Failed to deliver message to AppKey[...]; Event[...: dataChanged,
 * DataWearableServiceEvent(/opengym/wear)]`.
 *
 * `onDataChanged` is only half of it. A process that starts **after** the write never receives a
 * change event for it, so [WearableDataLayerClient.readItem] also reads the stored item at startup
 * and on node connect. Both paths end here.
 *
 * It is deliberately the *only* path. Registering `MessageClient.addListener` as well is the common
 * way to get every message delivered twice, because Play services routes to both the service and the
 * in-process listener — and `DataClient.addListener` would duplicate the item the same way.
 *
 * The service holds no state and does no decoding: it hands the raw payload to [DataLayerInbox] and
 * returns. Everything that can be reasoned about — routing by path, decoding, revisions — happens in
 * [DataLayerTransport] and [TransportWorkoutRepository], which are testable without a device.
 * Neither handler checks the path for the same reason: one filter covers `/opengym`, the transport
 * drops anything that is not `/opengym/wear`, and a second place to disagree is exactly what of
 * the decisions document exists to prevent.
 */
class OpenGymWearListenerService : WearableListenerService() {

  override fun onMessageReceived(event: MessageEvent) {
    DataLayerInbox.shared.publish(
      DataLayerMessage(
        nodeId = event.sourceNodeId,
        path = event.path,
        data = event.data,
      ),
    )
  }

  /**
   * The `DataItem` half: the phone's `state` frame, arriving as a change to `/opengym/wear`.
   *
   * A deletion carries no payload and is skipped; an empty payload is skipped for the same reason.
   * The URI's authority is the node that wrote the item, which is how the payload keeps a node id
   * even though a data event has no `sourceNodeId`.
   */
  override fun onDataChanged(events: DataEventBuffer) {
    try {
      for (event in events) {
        if (event.type != DataEvent.TYPE_CHANGED) continue
        val item = event.dataItem ?: continue
        val uri = item.uri ?: continue
        val data = item.data ?: continue
        if (data.isEmpty()) continue
        DataLayerInbox.shared.publish(
          DataLayerMessage(
            nodeId = uri.authority.orEmpty(),
            path = uri.path.orEmpty(),
            data = data,
          ),
        )
      }
    } finally {
      // The buffer is a cursor over a binder-backed store, not an array: not releasing it leaks the
      // store's shared memory, and Play services is in another process.
      events.release()
    }
  }
}

/**
 * The hand-off between [OpenGymWearListenerService] — which Android may start in a process that has
 * nothing else running — and whichever [WearableDataLayerClient] the app has built.
 *
 * A [Channel] rather than a `SharedFlow(replay = 0)` for the same reason [Transport.incoming] is one:
 * a frame that arrives before the transport has started collecting must not be silently dropped.
 *
 * **It is a single-consumer queue.** `receiveAsFlow()` fans elements out to whichever collector
 * asks, so two live clients in one process would steal each other's frames. There is exactly one
 * `MainActivity` and therefore one client at a time; `Log.w` in [publish] would not help, so this
 * constraint is documented rather than enforced. It is also the reason the inbox is never closed:
 * a process can outlive any individual activity.
 */
class DataLayerInbox {

  private val messages = Channel<DataLayerMessage>(Channel.UNLIMITED)

  val incoming: Flow<DataLayerMessage> = messages.receiveAsFlow()

  fun publish(message: DataLayerMessage) {
    if (!messages.trySend(message).isSuccess) {
      Log.w(TAG, "inbox rejected ${message.path} from ${message.nodeId}")
    }
  }

  companion object {
    private const val TAG = "DataLayerInbox"

    /** The process-wide inbox the listener service and the client both use. */
    val shared: DataLayerInbox = DataLayerInbox()
  }
}
