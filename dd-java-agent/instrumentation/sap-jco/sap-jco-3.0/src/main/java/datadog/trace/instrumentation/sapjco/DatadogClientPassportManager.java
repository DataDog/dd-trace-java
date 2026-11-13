package datadog.trace.instrumentation.sapjco;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import com.sap.conn.jco.ext.ClientPassportManager;
import com.sap.jdsr.writer.DsrIPassport;
import datadog.trace.api.Config;
import datadog.trace.api.DDTraceId;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JCo calls this once per outbound RFC call to obtain the SAP Extended Passport to send. We build
 * the passport from the currently active Datadog span (the client span opened around {@code
 * JCoFunction.execute} by {@link JCoClientInstrumentation}), so SAP joins that span's trace. When
 * there is no active trace we return {@code null} and JCo sends no Datadog passport.
 *
 * <p>Registered globally via {@code Environment.registerClientPassportManager} by {@link
 * PassportRegistry}. This class and {@link DatadogPassport} are injected into JCo's classloader as
 * helper classes.
 */
public final class DatadogClientPassportManager implements ClientPassportManager {

  private static final Logger log = LoggerFactory.getLogger(DatadogClientPassportManager.class);

  /**
   * ASCII marker appended to the span id so dd-sap-mon recognises a Datadog-issued connection id.
   */
  private static final byte[] DATADOG_MARKER = "DATADOG!".getBytes(StandardCharsets.US_ASCII);

  @Override
  public DsrIPassport callStarted(int callId, String destination, String function) {
    AgentSpan span = activeSpan();
    if (span == null) {
      log.debug("no active span for {} on {}; sending no passport", function, destination);
      return null;
    }
    DDTraceId traceId = span.getTraceId();
    if (traceId == null || DDTraceId.ZERO.equals(traceId)) {
      return null; // no real trace to propagate
    }
    boolean kept = isTraceKept(span);
    byte[] rootContextId = traceIdBytes(traceId);
    byte[] connectionId = connectionIdBytes(span.getSpanId());
    if (log.isDebugEnabled()) {
      log.debug(
          "building SAP passport for {}: traceId={} spanId={} kept={}",
          function,
          traceId.toHexString(),
          Long.toHexString(span.getSpanId()),
          kept);
    }
    return new DatadogPassport(
        rootContextId,
        connectionId,
        Config.get().getServiceName(),
        stringTag(span, "sap.user"),
        stringTag(span, "sap.client"),
        function,
        kept);
  }

  /**
   * Whether this span's trace will be kept. We force the sampling decision now (JCo asks for the
   * passport as the call starts) so SAP only runs end-to-end tracing for traces Datadog will keep.
   */
  private static boolean isTraceKept(AgentSpan span) {
    Integer priority = span.forceSamplingDecision();
    return priority != null && priority > 0;
  }

  @Override
  public void callFinished(int callId, long bytesSent, long bytesReceived) {
    // The client span lifecycle is driven by the execute() advice; nothing to do here.
  }

  /** 16 big-endian bytes of the 128-bit Datadog trace id (high 64 bits, then low 64 bits). */
  private static byte[] traceIdBytes(DDTraceId traceId) {
    return ByteBuffer.allocate(16)
        .putLong(traceId.toHighOrderLong())
        .putLong(traceId.toLong())
        .array();
  }

  /** 16 bytes: the 8 big-endian bytes of the span id followed by the "DATADOG!" marker. */
  private static byte[] connectionIdBytes(long spanId) {
    return ByteBuffer.allocate(16).putLong(spanId).put(DATADOG_MARKER).array();
  }

  private static String stringTag(AgentSpan span, String key) {
    Object value = span.getTag(key);
    return value == null ? "" : value.toString();
  }
}
