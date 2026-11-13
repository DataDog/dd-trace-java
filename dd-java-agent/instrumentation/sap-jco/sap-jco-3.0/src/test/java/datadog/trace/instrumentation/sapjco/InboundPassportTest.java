package datadog.trace.instrumentation.sapjco;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.sap.jdsr.writer.DsrIPassport;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class InboundPassportTest {

  /** Minimal DsrIPassport exposing just the root/connection id bytes the decoder reads. */
  private static DsrIPassport passport(byte[] root, byte[] conn) {
    return new DsrIPassport() {
      @Override
      public byte[] getRootContextIdBytes() {
        return root;
      }

      @Override
      public byte[] getConnectionIdBytes() {
        return conn;
      }

      @Override
      public byte[] getNetPassport() {
        return null;
      }

      @Override
      public String getCompName() {
        return null;
      }

      @Override
      public String getTransId() {
        return null;
      }

      @Override
      public boolean isValid() {
        return true;
      }

      @Override
      public boolean setByNetPassport(byte[] netPassport) {
        return false;
      }

      @Override
      public void setConnectionCounter(int counter) {}

      @Override
      public void setConnectionIdBytes(byte[] connectionId) {}

      @Override
      public void clear() {}
    };
  }

  private static byte[] datadogConnectionId(long spanId) {
    return ByteBuffer.allocate(16)
        .putLong(spanId)
        .put("DATADOG!".getBytes(StandardCharsets.US_ASCII))
        .array();
  }

  @Test
  void decodesDatadogIssuedPassport() {
    byte[] root =
        ByteBuffer.allocate(16).putLong(0x0011223344556677L).putLong(0x8899aabbccddeeffL).array();
    InboundPassport inbound =
        InboundPassport.from(passport(root, datadogConnectionId(0x0102030405060708L)));
    assertEquals("00112233445566778899aabbccddeeff", inbound.traceIdHex);
    assertEquals("0102030405060708", inbound.parentSpanIdHex);
  }

  @Test
  void ignoresPassportWithoutDatadogMarker() {
    byte[] root = new byte[16];
    byte[] conn = new byte[16]; // no "DATADOG!" marker
    assertNull(InboundPassport.from(passport(root, conn)));
  }

  @Test
  void ignoresNullOrShortPassport() {
    assertNull(InboundPassport.from(null));
    assertNull(InboundPassport.from(passport(new byte[8], new byte[16])));
  }
}
