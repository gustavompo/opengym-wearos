package ch.duartesantos.opengym.wear.link

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableStatusCodes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * [DataLayerClient] backed by `play-services-wearable`.
 *
 * **This is the one class in the watch's link stack that nothing here can test.** Neither emulator on
 * this machine has a Google account, the Wear companion app cannot be installed without one, and
 * every Data Layer call therefore fails with `ApiException: 4000 TARGET_NODE_NOT_CONNECTED`
 * (`docs/research/04-datalayer-emulator-spike.md`, `docs/notes/12-datalayer-decisions.md` §7). What
 * *is* tested is everything above it, through a fake implementation of [DataLayerClient]: node
 * selection, disconnection, recovery, routing, retention.
 *
 * Two deliberate choices:
 *
 * 1. **There is no `MessageClient.addListener`.** The single receive path is the manifest-registered
 *    [OpenGymWearListenerService], which hands messages to [DataLayerInbox]. Registering an
 *    in-process listener as well is the usual way to get every message delivered *twice*, because
 *    Play services routes to both. It also would not be the path that works when the watch app has
 *    been killed, which is the entire reason the Data Layer is being used instead of the TCP relay.
 * 2. **Nothing here throws.** Play services missing, Play services not available, no phone paired —
 *    all of them are "no node", so the app says "phone not connected" rather than crashing
 *    (`docs/research/04-datalayer-emulator-spike.md` §2a, where an unhandled `ApiException` killed
 *    the phone-side spike app outright).
 *
 * @param context an application context; held for the lifetime of the client.
 * @param capability the phone's advertised capability to prefer. The watch falls back to
 *   `NodeClient.connectedNodes` when no node advertises it, so the watch keeps working against a
 *   phone build that has not declared the capability yet.
 */
class WearableDataLayerClient(
  private val context: Context,
  private val capability: String = DataLayerContract.CAPABILITY_PHONE,
  private val scope: CoroutineScope = defaultTransportScope(),
  private val inbox: DataLayerInbox = DataLayerInbox.shared,
) : DataLayerClient {

  private val nodeChangeSignals =
    Channel<Unit>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

  private val capabilityListener =
    CapabilityClient.OnCapabilityChangedListener { info ->
      if (info.name == capability) nodeChangeSignals.trySend(Unit)
    }

  private var listenerJob: Job? = null

  override val incoming: Flow<DataLayerMessage> = inbox.incoming

  override val nodeChanges: Flow<Unit> = nodeChangeSignals.receiveAsFlow()

  init {
    listenerJob =
      scope.launch {
        runCatching {
          // Registration is what makes a phone appearing or disappearing visible quickly; the
          // transport re-queries on its own schedule regardless, so failing here is not fatal.
          Wearable.getCapabilityClient(context).addListener(capabilityListener, capability)
        }
          .onFailure { Log.w(TAG, "capability listener not registered: $it") }
      }
  }

  override suspend fun reachablePhoneNodes(): List<PhoneNode> =
    // Play services calls block, and the transport asks for this on a schedule.
    withContext(Dispatchers.IO) {
      val byCapability = queryNodes("capability $capability") {
        Wearable.getCapabilityClient(context)
          .getCapability(capability, CapabilityClient.FILTER_REACHABLE)
          .await()
          .nodes
      }
      val byConnected = queryNodes("connectedNodes") {
        Wearable.getNodeClient(context).connectedNodes.await()
      }
      // A node that advertises the capability and a node in `connectedNodes` are the same node; the
      // union is what makes the watch work against a phone that has not declared the capability.
      (byCapability + byConnected).distinctBy { it.id }
    }

  override suspend fun send(nodeId: String, path: String, payload: ByteArray): SendOutcome =
    try {
      Wearable.getMessageClient(context).sendMessage(nodeId, path, payload).await()
      // `MessageClient` has no persistence and no retry: success means "handed to the Data Layer".
      // Delivery is at-least-once at best, which is exactly the contract the repository assumes
      // (`docs/protocol.md` §5.3).
      SendOutcome.Delivered
    } catch (e: ApiException) {
      when (e.statusCode) {
        WearableStatusCodes.TARGET_NODE_NOT_CONNECTED,
        ConnectionResult.API_UNAVAILABLE -> SendOutcome.NotConnected
        else -> {
          Log.w(TAG, "sendMessage to $nodeId on $path failed: ${e.statusCode}", e)
          SendOutcome.Failed(e)
        }
      }
    } catch (e: Exception) {
      // Includes CancellationException from `await()`; the caller's cancellation is not ours to
      // swallow, but `Transport.send` may not throw, so it becomes an outcome like everything else.
      Log.w(TAG, "sendMessage to $nodeId on $path threw", e)
      SendOutcome.Failed(e)
    }

  /**
   * Reads the stored item, which is the half of §1a that no change event can cover: a process that
   * starts after the write has nothing to be notified about.
   *
   * Two queries rather than one because the URI matcher's wildcard behaviour for an authority-less
   * `wear:/<path>` URI is the one thing here that cannot be checked on this machine (no paired node,
   * no Google account — §7). The first query is the documented form and answers in one call; the
   * second asks for every item in the local store and filters by path locally, which is the same
   * question with no matching rule left to get wrong. Both are cheap and this runs once per connect,
   * not in a loop.
   */
  override suspend fun readItem(path: String): DataLayerMessage? =
    withContext(Dispatchers.IO) {
      val byPath = queryItems("items at $path", Uri.Builder().scheme(URI_SCHEME).path(path).build())
      byPath.firstOrNull { it.path == path }
        ?: queryItems("all items", null).firstOrNull { it.path == path }
    }

  /**
   * One data-item query, reduced to the payloads this app cares about. Never throws: no Play
   * services, no store, an already-released buffer are all "nothing stored".
   */
  private suspend fun queryItems(label: String, uri: Uri?): List<DataLayerMessage> =
    try {
      val client = Wearable.getDataClient(context)
      val task = if (uri == null) client.dataItems else client.getDataItems(uri)
      task.await().use { buffer ->
        buffer.mapNotNull { item ->
          val itemUri = item.uri ?: return@mapNotNull null
          val data = item.data ?: return@mapNotNull null
          // A deletion leaves the item behind with no payload; there is nothing to deliver.
          if (data.isEmpty()) return@mapNotNull null
          DataLayerMessage(nodeId = itemUri.authority.orEmpty(), path = itemUri.path.orEmpty(), data = data)
        }
      }
    } catch (e: Exception) {
      Log.d(TAG, "data item query '$label' unavailable: $e")
      emptyList()
    }

  override fun close() {
    listenerJob?.cancel()
    listenerJob = null
    runCatching {
      // Best effort: `removeListener` returns a Task nobody needs to wait for.
      Wearable.getCapabilityClient(context).removeListener(capabilityListener, capability)
    }
    nodeChangeSignals.close()
  }

  private suspend fun queryNodes(label: String, query: suspend () -> Collection<Node>): List<PhoneNode> =
    try {
      query().map { PhoneNode(id = it.id, displayName = it.displayName, nearby = it.isNearby) }
    } catch (e: Exception) {
      // No Play services, no paired device, no Google account: all "no node". Logged once per query
      // at debug level because on an unpaired device this is the normal state, not an error.
      Log.d(TAG, "node query '$label' unavailable: $e")
      emptyList()
    }

  companion object {
    private const val TAG = "WearableDataLayer"

    /** Data Layer URIs are `wear://<node>/<path>`; the node is a wildcard when reading. */
    private const val URI_SCHEME = "wear"
  }
}
