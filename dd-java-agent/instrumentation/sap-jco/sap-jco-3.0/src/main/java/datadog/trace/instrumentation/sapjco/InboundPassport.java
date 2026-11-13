package datadog.trace.instrumentation.sapjco;

import com.sap.jdsr.writer.DsrIPassport;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The Datadog ids decoded from an inbound SAP passport on the RFC server path: the trace id (from
 * the passport's root context id) and the caller's client span id (from the passport's connection
 * id, when it carries the {@code "DATADOG!"} marker this instrumentation writes on the client
 * side).
 *
 * <p>Carried from {@link DatadogServerPassportManager#requestStarted} to the {@code handleRequest}
 * advice on the same worker thread via a {@link ThreadLocal}.
 */
public final class InboundPassport {

  private static final byte[] DATADOG_MARKER = "DATADOG!".getBytes(StandardCharsets.US_ASCII);

  private static final ThreadLocal<InboundPassport> CURRENT = new ThreadLocal<>();

  final String traceIdHex; // 32 lower-case hex chars (128-bit)
  final String parentSpanIdHex; // 16 lower-case hex chars (64-bit)

  private InboundPassport(String traceIdHex, String parentSpanIdHex) {
    this.traceIdHex = traceIdHex;
    this.parentSpanIdHex = parentSpanIdHex;
  }

  /** Decodes a Datadog-issued passport, or {@code null} if it is absent or not Datadog-issued. */
  static InboundPassport from(DsrIPassport passport) {
    if (passport == null) {
      return null;
    }
    byte[] root = passport.getRootContextIdBytes();
    byte[] conn = passport.getConnectionIdBytes();
    if (root == null || root.length < 16 || conn == null || conn.length < 16) {
      return null;
    }
    // Only treat it as ours when the connection id carries the "DATADOG!" marker.
    if (!Arrays.equals(Arrays.copyOfRange(conn, 8, 16), DATADOG_MARKER)) {
      return null;
    }
    return new InboundPassport(hex(root, 0, 16), hex(conn, 0, 8));
  }

  static void set(InboundPassport passport) {
    CURRENT.set(passport);
  }

  public static InboundPassport getAndClear() {
    InboundPassport passport = CURRENT.get();
    if (passport != null) {
      CURRENT.remove();
    }
    return passport;
  }

  private static String hex(byte[] bytes, int from, int to) {
    StringBuilder sb = new StringBuilder((to - from) * 2);
    for (int i = from; i < to; i++) {
      sb.append(Character.forDigit((bytes[i] >> 4) & 0xf, 16));
      sb.append(Character.forDigit(bytes[i] & 0xf, 16));
    }
    return sb.toString();
  }
}
