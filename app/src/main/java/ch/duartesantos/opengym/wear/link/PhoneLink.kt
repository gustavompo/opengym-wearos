package ch.duartesantos.opengym.wear.link

import kotlinx.coroutines.flow.StateFlow

/**
 * What the UI is allowed to know about the phone link, in the terms the screens can show.
 *
 * [LinkStatus] is the transport-level truth (negotiated version, staleness, pending commands). This
 * is the one bit of it the UI needs, kept separate so that "no workout is running on the phone" and
 * "we cannot see the phone at all" are different sentences on the screen
 * (`docs/notes/12-datalayer-decisions.md` §6, `docs/protocol.md` §6).
 */
data class PhoneLinkState(
  /** `"datalayer"`, `"tcp"`, … — for logs and a future debug screen. */
  val transportName: String = "",
  /** True while the transport has a phone it can actually send to. */
  val connected: Boolean = false,
  /** False when the version intersection was empty (`docs/protocol.md` §2). */
  val compatible: Boolean = true,
  /** `sentAt` of the newest accepted `state`, or null if none has ever arrived. */
  val lastStateAtMillis: Long? = null,
)

/**
 * Implemented by repositories that are backed by a phone link.
 *
 * A separate interface rather than a member of [ch.duartesantos.opengym.wear.data.WorkoutRepository]
 * on purpose: [ch.duartesantos.opengym.wear.data.FakeWorkoutRepository] has no phone and no link, and
 * making every repository answer a link question would invite the fake to answer it wrongly.
 */
interface PhoneLinkSource {

  /** Link health. A `StateFlow` so a late collector sees the current value, like the workout. */
  fun observePhoneLink(): StateFlow<PhoneLinkState>
}

/**
 * The text the exercise page shows when there is no workout to render.
 *
 * This is the whole of `12-datalayer-decisions.md` §6 made visible, plus §1a's rule that a status
 * surface must not report two different situations with one string. A watch showing a
 * plausible-looking workout it never received is indistinguishable from a live one — it has already
 * fooled one reader of this project — so "we cannot see the phone" must not render as
 * "no active workout". Four situations, four readings:
 *
 * | situation | text |
 * |---|---|
 * | no phone link at all (fake, previews, instrumented suite) | "No active workout" |
 * | protocol versions do not intersect | "Update the phone app" |
 * | link down, or up with no `state` ever received | "Phone not connected" |
 * | connected, and the phone said there is no workout | "No active workout" |
 *
 * The incompatibility branch is checked **before** [PhoneLinkState.connected] because an empty
 * version intersection leaves the transport `Connected` (`docs/protocol.md` §2: a pipe that moves
 * bytes is not the layer that decides whether they mean anything). Testing `connected` first would
 * therefore make "Update the phone app" unreachable on the one path that produces it, and the user
 * would be told there is no workout running by a phone that cannot be understood.
 *
 * `link == null` and the last row deliberately share a sentence: a repository with no phone and a
 * phone that has told us there is no workout really are the same statement about the workout. It is
 * the *never heard anything* case that must not borrow it — see the branch comment in the body.
 */
fun emptyWorkoutMessage(link: PhoneLinkState?): String =
  when {
    // No phone link at all: the fake, the previews, the instrumented suite. There is nothing to be
    // disconnected from, so the original sentence is the correct one and stays.
    link == null -> "No active workout"
    !link.compatible -> "Update the phone app"
    // The link is up (the node is reachable) but no `state` has ever arrived. `No active workout`
    // here is a claim the watch has no way to support: the phone publishes a `state` on every
    // `hello` — with `workout: null` when nothing is running — so "no frame ever" means the phone is
    // not answering, not that the phone is idle. Reporting the idle sentence while a carrier is
    // broken is exactly the lie the hardware run caught (`docs/notes/11a-device-handoff-shepherd.md`
    // §3, `12-datalayer-decisions.md` §1a), and it is also what a stopped phone app looks like: an
    // unreachable *node* is reported as a failed send, but a stopped *app* leaves the node ledger
    // green and simply stops publishing.
    !link.connected || link.lastStateAtMillis == null -> "Phone not connected"
    else -> "No active workout"
  }
