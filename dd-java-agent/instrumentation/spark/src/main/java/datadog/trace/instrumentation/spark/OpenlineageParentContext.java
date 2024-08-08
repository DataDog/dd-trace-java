package datadog.trace.instrumentation.spark;

import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTraceCollector;
import datadog.trace.bootstrap.instrumentation.api.PathwayContext;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Optional;
import org.apache.spark.SparkConf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OpenlineageParentContext implements AgentSpan.Context {
  private static final Logger log = LoggerFactory.getLogger(OpenlineageParentContext.class);

  private final DDTraceId traceId;
  private final long spanId;

  private final String parentJobNamespace;
  private final String parentJobName;
  private final String parentRunId;

  public static Optional<OpenlineageParentContext> from(SparkConf sparkConf) {
    if (!sparkConf.contains("spark.openlineage.parentRunId")) {
      return Optional.empty();
    }
    return Optional.of(
        new OpenlineageParentContext(
            sparkConf.get("spark.openlineage.parentJobNamespace"),
            sparkConf.get("spark.openlineage.parentJobName"),
            sparkConf.get("spark.openlineage.parentRunId")));
  }

  OpenlineageParentContext(String parentJobNamespace, String parentJobName, String parentRunId) {
    log.debug(
        "Creating OpenlineageParentContext with parentJobNamespace: {}, parentJobName: {}, parentRunId: {}",
        parentJobNamespace,
        parentJobName,
        parentRunId);

    this.parentJobNamespace = parentJobNamespace;
    this.parentJobName = parentJobName;
    this.parentRunId = parentRunId;

    MessageDigest digest = null;
    try {
      digest = MessageDigest.getInstance("SHA-1");
    } catch (NoSuchAlgorithmException e) {
      log.debug("Unable to find SHA-1 algorithm", e);
    }

    if (digest != null && parentJobNamespace != null && parentRunId != null) {
      traceId = computeTraceId(digest, parentJobNamespace, parentJobName, parentRunId);
      spanId = computeSpanId(digest, parentJobNamespace, parentRunId);
    } else {
      traceId = DDTraceId.ZERO;
      spanId = DDSpanId.ZERO;
    }

    log.debug("Created OpenlineageParentContext with traceId: {}, spanId: {}", traceId, spanId);
  }

  private long computeSpanId(MessageDigest digest, String parentJobNamespace, String parentRunId) {
    byte[] hash = digest.digest(parentRunId.getBytes(StandardCharsets.UTF_8));
    return ByteBuffer.wrap(hash).getLong();
  }

  private DDTraceId computeTraceId(
      MessageDigest digest, String parentJobNamespace, String parentJobName, String parentRunId) {
    // TODO: implement this
    return DDTraceId.ZERO;
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

  public String getParentJobNamespace() {
    return parentJobNamespace;
  }

  public String getParentJobName() {
    return parentJobName;
  }

  public String getParentRunId() {
    return parentRunId;
  }
}
