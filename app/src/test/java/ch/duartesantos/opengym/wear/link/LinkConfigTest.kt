package ch.duartesantos.opengym.wear.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Transport selection from intent extras.
 *
 * This is decision 's contract in code, and the part of it that is easiest to get silently wrong:
 * what a *plain* launch does. It is pure Kotlin — `fromArgs` takes three nullable values and an
 * `Intent` is unpacked by the caller — so it is testable here with no device and no Android.
 */
class LinkConfigTest {

  @Test
  fun `a plain launch is the data layer, never the fake`() {
    assertEquals(TransportKind.DATALAYER, TransportKind.DEFAULT)
    assertEquals(TransportKind.DATALAYER, LinkConfig().transport)
    assertEquals(TransportKind.DATALAYER, LinkConfig.fromArgs(null, null, null).transport)
  }

  @Test
  fun `an asked-for override is honoured`() {
    assertEquals(TransportKind.FAKE, LinkConfig.fromArgs("fake", null, null).transport)
    assertEquals(TransportKind.TCP, LinkConfig.fromArgs("tcp", null, null).transport)
    assertEquals(TransportKind.DATALAYER, LinkConfig.fromArgs("datalayer", null, null).transport)
  }

  /**
   * The one the task called out. An unknown string is "no override", and no override is the
   * **production** path — a stale `adb am start` line from the TCP era must not silently drop the
   * user onto an in-memory fake workout that looks exactly like real data
   * .
   */
  @Test
  fun `an unknown override does not silently become a fake`() {
    assertNull(TransportKind.fromOverride("nonsense"))
    assertNull(TransportKind.fromOverride(""))
    assertNull(TransportKind.fromOverride("   "))

    val config = LinkConfig.fromArgs("nonsense", null, null)
    assertEquals(TransportKind.DATALAYER, config.transport)
    assertFalse(config.transport == TransportKind.FAKE)
    assertFalse(config.transport == TransportKind.TCP)

    // `fromWire` is the total wrapper: same unknown string, still the default.
    assertEquals(TransportKind.DATALAYER, TransportKind.fromWire("nonsense"))
    assertEquals(TransportKind.DATALAYER, TransportKind.fromWire(null))
  }

  @Test
  fun `override matching ignores case and surrounding whitespace`() {
    assertEquals(TransportKind.FAKE, TransportKind.fromOverride("  FAKE "))
    assertEquals(TransportKind.TCP, TransportKind.fromOverride("Tcp"))
    assertEquals(TransportKind.DATALAYER, TransportKind.fromOverride("DataLayer"))
  }

  @Test
  fun `tcp host and port fall back to the emulator defaults when absent`() {
    val config = LinkConfig.fromArgs(transport = "tcp", tcpHost = null, tcpPort = null)
    assertEquals(TcpRelayTransport.EMULATOR_HOST, config.tcpHost)
    assertEquals(TcpRelayTransport.DEFAULT_PORT, config.tcpPort)
  }

  @Test
  fun `a provided tcp host and port are used`() {
    val config = LinkConfig.fromArgs(transport = "tcp", tcpHost = "192.168.1.10", tcpPort = 9999)
    assertEquals("192.168.1.10", config.tcpHost)
    assertEquals(9999, config.tcpPort)
  }

  @Test
  fun `a blank host and an out-of-range port fall back rather than being passed through`() {
    // `MainActivity` already maps "no extra" to null (`getIntExtra(..., 0).takeIf { it > 0 }`), but
    // `fromArgs` repairs a bad value itself: a nonsensical port must not become a socket to nowhere
    // when the fallback is a working dev loop.
    assertEquals(TcpRelayTransport.EMULATOR_HOST, LinkConfig.fromArgs("tcp", "   ", null).tcpHost)
    assertEquals(TcpRelayTransport.DEFAULT_PORT, LinkConfig.fromArgs("tcp", null, 0).tcpPort)
    assertEquals(TcpRelayTransport.DEFAULT_PORT, LinkConfig.fromArgs("tcp", null, -1).tcpPort)
    assertEquals(TcpRelayTransport.DEFAULT_PORT, LinkConfig.fromArgs("tcp", null, 65_536).tcpPort)

    assertEquals(1, LinkConfig.fromArgs("tcp", null, 1).tcpPort)
    assertEquals(65_535, LinkConfig.fromArgs("tcp", null, 65_535).tcpPort)
  }

  @Test
  fun `every transport kind has a distinct wire word`() {
    val wires = TransportKind.entries.map { it.wire }
    assertEquals(wires.size, wires.toSet().size)
    TransportKind.entries.forEach { assertEquals(it, TransportKind.fromOverride(it.wire)) }
  }
}
