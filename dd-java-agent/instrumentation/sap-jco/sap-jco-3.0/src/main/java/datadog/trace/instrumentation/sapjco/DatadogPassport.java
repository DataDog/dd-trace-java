package datadog.trace.instrumentation.sapjco;

import com.sap.jdsr.writer.DsrIPassport;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SAP Extended Passport (EPP v3, 230 bytes) that carries the active Datadog trace into SAP. SAP
 * stores the passport unchanged in its statistics records; dd-sap-mon reads it back and rebuilds
 * the SAP side of the trace:
 *
 * <ul>
 *   <li><b>root context id</b> = the 128-bit Datadog trace id → becomes the SAP trace id;
 *   <li><b>connection id</b> = the 8-byte client span id + ASCII {@code "DATADOG!"} → makes the SAP
 *       span a child of that client span.
 * </ul>
 *
 * <p>The byte layout is an external contract: it must match dd-sap-mon's parser and the canonical C
 * implementation ({@code dd-sap-test/src/common/epp.c}). A one-byte offset error silently breaks
 * the SAP↔caller join, so the field order and widths below are fixed.
 */
final class DatadogPassport implements DsrIPassport {

  private static final Logger log = LoggerFactory.getLogger(DatadogPassport.class);

  /** EPP protocol version. */
  private static final byte VERSION = 3;

  /** Total serialized size of an EPP v3 passport. */
  private static final int LENGTH = 230;

  /**
   * Forced SAP end-to-end trace-flag set, requested only when the Datadog trace is kept. For a
   * dropped trace no flags are set (0x0000), so SAP does not spend resources on end-to-end tracing
   * for a request whose trace will be thrown away.
   */
  private static final int E2E_FORCED = 0x1D9F;

  /** "*TH*" marker that opens and closes the passport. */
  private static final byte[] MARKER = {0x2a, 0x54, 0x48, 0x2a};

  private final byte[] rootContextId; // 16 bytes: Datadog trace id
  private final byte[] connectionId; // 16 bytes: span id + "DATADOG!"
  private final String componentName;
  private final String user;
  private final String client;
  private final String action; // the RFC function name
  private final boolean traceKept; // whether the Datadog trace will be kept

  private int connectionCounter;

  DatadogPassport(
      byte[] rootContextId,
      byte[] connectionId,
      String componentName,
      String user,
      String client,
      String action,
      boolean traceKept) {
    this.rootContextId = rootContextId;
    this.connectionId = connectionId;
    this.componentName = componentName;
    this.user = user;
    this.client = client;
    this.action = action;
    this.traceKept = traceKept;
  }

  @Override
  public byte[] getNetPassport() {
    ByteBuffer b = ByteBuffer.allocate(LENGTH); // ByteBuffer is big-endian by default
    b.put(MARKER).put(VERSION).putShort((short) LENGTH);
    // trace flags are stored little-endian; force end-to-end tracing only for kept traces.
    int e2eFlags = traceKept ? E2E_FORCED : 0;
    b.put((byte) (e2eFlags & 0xff)).put((byte) (e2eFlags >> 8));
    padded(b, 32, componentName);
    b.putShort((short) 0); // service
    padded(b, 32, user);
    padded(b, 40, action);
    b.putShort((short) 0); // action type
    padded(b, 32, ""); // previous component
    padded(b, 32, ""); // transaction id
    padded(b, 3, client);
    b.putShort((short) 0); // component type
    b.put(rootContextId).put(connectionId).putInt(connectionCounter);
    b.putShort((short) 0).putShort((short) 226); // no variable parts
    b.put(MARKER); // trailer
    if (log.isDebugEnabled()) {
      log.debug(
          "JCo fetched SAP passport for {}: e2eFlags=0x{} rootContextId={} connectionId={}",
          action,
          Integer.toHexString(e2eFlags),
          hex(rootContextId),
          hex(connectionId));
    }
    return b.array();
  }

  private static String hex(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
    }
    return sb.toString();
  }

  @Override
  public byte[] getRootContextIdBytes() {
    return rootContextId;
  }

  @Override
  public byte[] getConnectionIdBytes() {
    return connectionId;
  }

  @Override
  public void setConnectionIdBytes(byte[] connectionId) {
    // Ignored: we keep our own span-id + "DATADOG!" connection id, which is what SAP must store.
  }

  @Override
  public void setConnectionCounter(int counter) {
    this.connectionCounter = counter;
  }

  @Override
  public String getCompName() {
    return componentName;
  }

  @Override
  public String getTransId() {
    return "";
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
  public void clear() {}

  /**
   * Writes {@code s} as {@code width} ASCII bytes, right-padded with spaces and truncated to fit.
   */
  private static void padded(ByteBuffer b, int width, String s) {
    byte[] v = (s == null ? "" : s).getBytes(StandardCharsets.US_ASCII);
    for (int i = 0; i < width; i++) {
      b.put(i < v.length ? v[i] : (byte) 0x20);
    }
  }
}
