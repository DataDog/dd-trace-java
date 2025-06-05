package datadog.trace.instrumentation.spark;

import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.datastreams.PathwayContext;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTraceCollector;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In the case of databricks, spark jobs are linked to the databricks task that launched them.
 *
 * <p>Databricks spark spans are generated from this spark instrumentation, while databricks
 * workflow task spans are generated from the databricks API. The traceId/spanId is computed using
 * the same hash on both sides to link everything in the same trace
 */
public class DatabricksParentContext implements AgentSpanContext {
  private static final Logger log = LoggerFactory.getLogger(DatabricksParentContext.class);

  private final DDTraceId traceId;
  private final long spanId;

  public DatabricksParentContext(String jobId, String jobRunId, String taskRunId) {
    MessageDigest digest = null;
    try {
      digest = MessageDigest.getInstance("SHA-1");
    } catch (NoSuchAlgorithmException e) {
      log.debug("Unable to find SHA-1 algorithm", e);
    }

    if (digest != null && jobId != null && taskRunId != null) {
      traceId = computeTraceId(digest, jobId, jobRunId, taskRunId);
      spanId = computeSpanId(digest, jobId, taskRunId);
    } else {
      traceId = DDTraceId.ZERO;
      spanId = DDSpanId.ZERO;
    }
  }

  private DDTraceId computeTraceId(
      MessageDigest digest, String jobId, String jobRunId, String taskRunId) {
    byte[] inputBytes;

    if (jobRunId != null) {
      inputBytes = (jobId + jobRunId).getBytes(StandardCharsets.UTF_8);
    } else {
      inputBytes = (jobId + taskRunId).getBytes(StandardCharsets.UTF_8);
    }

    byte[] hash = digest.digest(inputBytes);
    long traceIdLong = ByteBuffer.wrap(hash).getLong();
    return DDTraceId.from(traceIdLong);
  }

  private long computeSpanId(MessageDigest digest, String jobId, String taskRunId) {
    byte[] hash = digest.digest((jobId + taskRunId).getBytes(StandardCharsets.UTF_8));
    return ByteBuffer.wrap(hash).getLong();
  }

  @Override
  public DDTraceId getTraceId() {
    return traceId;
  }

  @Override
  public long getSpanId() {
    return spanId;
  }

  @Override
  public AgentTraceCollector getTraceCollector() {
    return null;
  }

  @Override
  public int getSamplingPriority() {
    return PrioritySampling.UNSET;
  }

  @Override
  public Iterable<Map.Entry<String, String>> baggageItems() {
    return null;
  }

  @Override
  public PathwayContext getPathwayContext() {
    return null;
  }

  @Override
  public boolean isRemote() {
    return false;
  }
}
