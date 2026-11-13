package datadog.trace.instrumentation.sapjco;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Golden-byte checks for the EPP v3 passport layout. The layout is an external contract shared with
 * dd-sap-mon and the canonical {@code epp.c}; these offsets must not drift.
 */
class DatadogPassportTest {

  private static final byte[] MARKER = {0x2a, 0x54, 0x48, 0x2a};

  private static byte[] build(int counter) {
    return build(counter, true);
  }

  private static byte[] build(int counter, boolean traceKept) {
    byte[] root = new byte[16];
    for (int i = 0; i < 16; i++) {
      root[i] = (byte) (0xA0 + i);
    }
    byte[] conn = new byte[16];
    for (int i = 0; i < 8; i++) {
      conn[i] = (byte) i;
    }
    System.arraycopy("DATADOG!".getBytes(StandardCharsets.US_ASCII), 0, conn, 8, 8);
    DatadogPassport passport =
        new DatadogPassport(
            root, conn, "dd-service", "DEVELOPER", "100", "RFC_READ_TABLE", traceKept);
    passport.setConnectionCounter(counter);
    return passport.getNetPassport();
  }

  @Test
  void hasExpectedFrameAndLength() {
    byte[] epp = build(0);
    assertEquals(230, epp.length);
    assertArrayEquals(MARKER, Arrays.copyOfRange(epp, 0, 4), "opening *TH* marker");
    assertArrayEquals(MARKER, Arrays.copyOfRange(epp, 226, 230), "closing *TH* marker");
    assertEquals(3, epp[4], "version");
    // length is a big-endian short
    assertEquals(230, ((epp[5] & 0xff) << 8) | (epp[6] & 0xff));
  }

  @Test
  void forcesE2eFlagsForKeptTraces() {
    byte[] epp = build(0, true);
    // E2E_FORCED (0x1D9F) trace flags are little-endian
    assertEquals((byte) 0x9F, epp[7]);
    assertEquals((byte) 0x1D, epp[8]);
  }

  @Test
  void leavesE2eFlagsUnsetForDroppedTraces() {
    byte[] epp = build(0, false);
    assertEquals((byte) 0x00, epp[7]);
    assertEquals((byte) 0x00, epp[8]);
  }

  @Test
  void carriesRootContextConnectionIdAndCounterAtFixedOffsets() {
    byte[] epp = build(7);
    byte[] root = new byte[16];
    for (int i = 0; i < 16; i++) {
      root[i] = (byte) (0xA0 + i);
    }
    assertArrayEquals(root, Arrays.copyOfRange(epp, 186, 202), "root context id");
    byte[] conn = Arrays.copyOfRange(epp, 202, 218);
    assertArrayEquals(
        "DATADOG!".getBytes(StandardCharsets.US_ASCII),
        Arrays.copyOfRange(conn, 8, 16),
        "DATADOG! marker in connection id");
    // connection counter is a big-endian int at offset 218
    int counter =
        ((epp[218] & 0xff) << 24)
            | ((epp[219] & 0xff) << 16)
            | ((epp[220] & 0xff) << 8)
            | (epp[221] & 0xff);
    assertEquals(7, counter);
  }

  @Test
  void padsTextFieldsWithSpaces() {
    byte[] epp = build(0);
    // component name occupies 32 bytes starting at offset 9, space-padded
    byte[] compName = Arrays.copyOfRange(epp, 9, 41);
    assertEquals("dd-service", new String(compName, StandardCharsets.US_ASCII).trim());
    assertEquals((byte) 0x20, compName[31], "padding byte");
  }
}
