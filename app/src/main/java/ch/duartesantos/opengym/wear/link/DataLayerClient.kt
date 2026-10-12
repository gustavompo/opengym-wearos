package ch.duartesantos.opengym.wear.link

import kotlinx.coroutines.flow.Flow

/** The fixed names both sides agree on. `docs/notes/12-datalayer-decisions.md` §1. */
object DataLayerContract {

  /**
   * The single message path, used for every frame in **both** directions. Routing by frame `type`
   * rather than by path keeps this side trivially symmetric with the TCP relay and leaves no second
   * place for the two ends to disagree (`12-datalayer-decisions.md` §1).
   */
  const val MESSAGE_PATH: String = "/opengym/wear"

  /** Declared by the phone fork in its own `res/values/wear.xml`; the watch queries for it. */
  const val CAPABILITY_PHONE: String = "opengym_phone"

  /** Declared by this module in `res/values/wear.xml`; how the phone discovers a watch node. */
  const val CAPABILITY_WATCH: String = "opengym_wear"
}

/**
 * A node the Data Layer can currently reach, reduced to what node selection actually needs.
 *
 * [nearby] is `Node.isNearby()`: true when the device is reachable directly (Bluetooth/Wi-Fi)
 * rather than only through the cloud relay. A cloud-only phone still works but adds a round trip
 * through Google's servers and requires the phone to have internet, so a nearby node is preferred.
 */
data class PhoneNode(val id: String, val displayName: String? = null, val nearby: Boolean = false)

/**
 * One payload that arrived from a node.
 *
 * The payload is raw bytes because that is what `MessageClient` carries: one discrete byte array per
 * message, so unlike TCP there is **no newline framing** to strip (`12-datalayer-decisions.md` §1).
 * One message is exactly one `WireProtocol` frame.
 */
class DataLayerMessage(val nodeId: String, val path: String, val data: ByteArray) {
  // ByteArray equality is identity in Kotlin, which silently breaks `assertEquals` in tests and any
  // `contains` on a list of these.
  override fun equals(other: Any?): Boolean =
    this === other ||
      (other is DataLayerMessage &&
        nodeId == other.nodeId &&
        path == other.path &&
        data.contentEquals(other.data))

  override fun hashCode(): Int = (nodeId.hashCode() * 31 + path.hashCode()) * 31 + data.contentHashCode()

  override fun toString(): String =
    "DataLayerMessage(nodeId=$nodeId, path=$path, bytes=${data.size})"
}

/**
 * The **thin injectable seam** over `play-services-wearable`.
 *
 * Everything about the Data Layer that has logic in it — choosing between nodes, noticing a
 * disconnect, retaining state through it, routing, rejecting — lives in [DataLayerTransport] and
 * [TransportWorkoutRepository], above this interface. That split is not decoration: the Data Layer
 * **cannot be exercised on this machine's emulators** (neither has a Google account, so every call
 * fails with `TARGET_NODE_NOT_CONNECTED` — `docs/notes/12-datalayer-decisions.md` §7), so a fake
 * implementation of this interface is the only way any of that logic is tested at all.
 *
 * Implementations must not throw. A missing phone, missing Play services, or a refused send is a
 * value, not an exception: `reachablePhoneNodes()` answers an empty list and `send()` answers a
 * [SendOutcome] that is not [SendOutcome.Delivered].
 *
 * Only the GMS-backed implementation, [WearableDataLayerClient], is exempt from being tested here,
 * and it is the one piece this document says is unverified.
 */
interface DataLayerClient {

  /**
   * Every message received from the Data Layer, in order, with no drops while nobody is collecting.
   * Implementations should back this with a channel for exactly that reason.
   */
  val incoming: Flow<DataLayerMessage>

  /**
   * A hint that the set of reachable nodes may have changed. Not a guarantee of delivery: the
   * transport re-queries on its own schedule regardless, because the underlying ledgers lag reality
   * (a pocketed phone can take seconds to be reported gone).
   */
  val nodeChanges: Flow<Unit>

  /** Reachable phone nodes right now. Empty when there is no phone, or no Play services. */
  suspend fun reachablePhoneNodes(): List<PhoneNode>

  /**
   * The payload currently stored at [path], with the node that wrote it, or null when nothing is
   * stored there.
   *
   * **This is not the same question as [incoming], and the difference cost a hardware run.**
   * [incoming] carries change events, and a change event only exists for a write that happened while
   * the Data Layer was watching the path. A watch process that starts *after* the phone published
   * therefore never sees one, and renders nothing forever from the item that is sitting right there
   * in the store (`docs/notes/12-datalayer-decisions.md` §1a, `11a-device-handoff-shepherd.md` §4).
   * So the stored value has to be read explicitly — at startup, and again when a node connects.
   *
   * Same no-throw contract as [send]: no item, no Play services, no permission, a closed buffer all
   * answer null. Duplicated delivery is harmless, which is what lets both carriers exist (§1a: the
   * `rev` high-water mark in `TransportWorkoutRepository` makes a repeat frame a no-op).
   */
  suspend fun readItem(path: String): DataLayerMessage?

  /** Sends one payload. Never throws; a dead link is a [SendOutcome]. */
  suspend fun send(nodeId: String, path: String, payload: ByteArray): SendOutcome

  /** Releases listeners and any scope this client owns. Idempotent. */
  fun close()
}

/**
 * Picks the one phone to talk to, deterministically.
 *
 * Rule, in order:
 *  1. A directly reachable node (`nearby`) beats a cloud-relay-only one — the relay adds a round
 *     trip through Google's servers and needs the phone to have its own internet.
 *  2. Ties are broken by node id, ascending. Node ids are opaque hex strings, so this is an
 *     arbitrary order, but it is a *stable* one: two watches paired with the same phone, or one
 *     watch paired with two phones, must not flap between targets on each rescan.
 *  3. Duplicate ids are collapsed, preferring whichever copy is `nearby`.
 *
 * Null when there is nothing to talk to.
 */
fun selectPhoneNode(nodes: List<PhoneNode>): PhoneNode? =
  nodes
    .groupBy { it.id }
    .map { (_, sameId) -> sameId.firstOrNull { it.nearby } ?: sameId.first() }
    .minWithOrNull(compareByDescending<PhoneNode> { it.nearby }.thenBy { it.id })
