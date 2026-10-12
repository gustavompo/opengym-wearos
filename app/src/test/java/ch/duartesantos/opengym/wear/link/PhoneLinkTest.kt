package ch.duartesantos.opengym.wear.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The logic that sits *beside* the Data Layer rather than inside it, and therefore the part of M3.4
 * that can actually be proven on this machine (`docs/notes/12-datalayer-decisions.md` §7).
 *
 * Two things live here:
 *
 *  - [selectPhoneNode] — decision §2 says the watch must not assume a single phone, so *which* phone
 *    is a rule that needs to be deterministic rather than incidental;
 *  - [emptyWorkoutMessage] — decision §6's whole visible surface: "we cannot see the phone" must not
 *    render as "no active workout". §1a adds the harder case: a *reachable node* that has never sent
 *    a `state` is not an idle workout either — that is exactly how the broken carrier read on
 *    hardware.
 */
class PhoneLinkTest {

  private fun node(id: String, nearby: Boolean = false, name: String? = null) =
    PhoneNode(id = id, displayName = name, nearby = nearby)

  // --- selectPhoneNode --------------------------------------------------------------------------

  @Test
  fun `no reachable node means no target`() {
    assertNull(selectPhoneNode(emptyList()))
  }

  @Test
  fun `a single node is the target`() {
    assertEquals("phone-1", selectPhoneNode(listOf(node("phone-1", nearby = true)))?.id)
  }

  @Test
  fun `a directly reachable node beats a cloud-relay-only one even when its id sorts later`() {
    // `aaa` is cloud-only (through Google's servers, and the phone needs its own internet); `zzz` is
    // reachable directly. Nearby wins regardless of the ids.
    val chosen = selectPhoneNode(listOf(node("aaa", nearby = false), node("zzz", nearby = true)))
    assertEquals("zzz", chosen?.id)
    assertTrue(chosen!!.nearby)
  }

  @Test
  fun `ties among nearby nodes are broken by ascending node id`() {
    val chosen =
      selectPhoneNode(
        listOf(node("phone-b", nearby = true), node("phone-a", nearby = true), node("phone-c", nearby = true)),
      )
    assertEquals("phone-a", chosen?.id)
  }

  @Test
  fun `ties among cloud-only nodes are broken by ascending node id too`() {
    val chosen = selectPhoneNode(listOf(node("phone-c"), node("phone-a"), node("phone-b")))
    assertEquals("phone-a", chosen?.id)
  }

  /**
   * The property that matters on hardware: a rescan that returns the same set in a different order
   * must not retarget the transport, because a retarget is a reconnect (and a re-sent `hello`). Two
   * watches paired with the same phone, or one watch paired with two, must not flap.
   */
  @Test
  fun `the choice does not depend on the order the nodes arrive in`() {
    val a = node("phone-a", nearby = true)
    val b = node("phone-b", nearby = true)
    val c = node("phone-c", nearby = false)

    assertEquals(selectPhoneNode(listOf(a, b, c)), selectPhoneNode(listOf(c, b, a)))
    assertEquals(selectPhoneNode(listOf(b, c, a)), selectPhoneNode(listOf(a, c, b)))
  }

  @Test
  fun `duplicate ids are collapsed, keeping the nearby copy`() {
    val cloudCopy = node("phone-1", nearby = false, name = "cloud view")
    val nearbyCopy = node("phone-1", nearby = true, name = "direct view")

    val chosen = selectPhoneNode(listOf(cloudCopy, nearbyCopy))
    assertEquals("phone-1", chosen?.id)
    assertTrue("the nearby copy is the one worth keeping", chosen!!.nearby)
    assertEquals("direct view", chosen.displayName)
  }

  @Test
  fun `a duplicated id is one candidate, not two`() {
    // If the duplicate were not collapsed this would be a two-element list and `minWith` would still
    // pick `phone-1` — so the assertion that matters is on the *chosen node's* fields.
    val nodes =
      listOf(
        node("phone-1", nearby = true, name = "first"),
        node("phone-1", nearby = true, name = "second"),
        node("phone-2", nearby = true),
      )
    val chosen = selectPhoneNode(nodes)
    assertEquals("phone-1", chosen?.id)
    assertEquals("first", chosen?.displayName)
  }

  // --- emptyWorkoutMessage ----------------------------------------------------------------------

  @Test
  fun `a repository with no link at all keeps the old wording`() {
    // The fake, the previews and the instrumented suite. There is no phone to be disconnected from.
    assertEquals("No active workout", emptyWorkoutMessage(null))
  }

  @Test
  fun `a reachable phone that has said there is no workout is no active workout`() {
    // The phone always answers a `hello` with its current projection, `workout: null` included
    // (`lib/wear-link.js`, onWatchHello -> publish({force:true})), so this branch means "a `state`
    // arrived and it was empty" — the one case the idle sentence is true in.
    assertEquals(
      "No active workout",
      emptyWorkoutMessage(PhoneLinkState(connected = true, lastStateAtMillis = 1_790_000_000_000L)),
    )
  }

  /**
   * The §1a defect, as a unit test: `connected` is about the *node*, not about the phone answering.
   *
   * A reachable node with no `state` ever received is the shape the broken carrier had on hardware —
   * handshake complete, node discovered, link `Connected`, payload discarded — and it also happens
   * when the phone's app was force-stopped, because stopping a package does not remove its device
   * from the node ledger. Claiming "No active workout" there confirms the wrong thing while the
   * watch cannot see the phone at all.
   */
  @Test
  fun `a reachable phone that never published cannot be reported as an idle workout`() {
    val message = emptyWorkoutMessage(PhoneLinkState(transportName = "datalayer", connected = true))
    assertEquals("Phone not connected", message)
    assertNotEquals(
      "a reachable node that never answered is not an idle workout",
      emptyWorkoutMessage(PhoneLinkState(transportName = "datalayer", connected = true, lastStateAtMillis = 1L)),
      message,
    )
  }

  @Test
  fun `an unreachable phone says so instead of inventing a workout`() {
    val message = emptyWorkoutMessage(PhoneLinkState(transportName = "datalayer", connected = false))
    assertEquals("Phone not connected", message)
    assertFalse("the sentence must not be mistakable for real data", message.contains("workout", ignoreCase = true))
  }

  @Test
  fun `a disconnected link and an idle workout read differently`() {
    // The rule §1a states outright, asserted as one comparison so it cannot drift apart again.
    val disconnected = emptyWorkoutMessage(PhoneLinkState(connected = false))
    val idle = emptyWorkoutMessage(
      PhoneLinkState(connected = true, lastStateAtMillis = 1_790_000_000_000L),
    )
    assertNotEquals(disconnected, idle)
  }

  @Test
  fun `an incompatible phone says to update the app, not that no workout is running`() {
    // `docs/protocol.md` §2: an empty version intersection leaves the *transport* Connected — a pipe
    // that moves bytes is not the layer that decides what they mean — so this state really occurs
    // with `connected = true`, and the incompatibility has to win the branch.
    assertEquals(
      "Update the phone app",
      emptyWorkoutMessage(PhoneLinkState(connected = true, compatible = false)),
    )
    assertEquals(
      "Update the phone app",
      emptyWorkoutMessage(PhoneLinkState(connected = false, compatible = false)),
    )
  }
}
