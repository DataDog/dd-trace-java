package datadog.trace.instrumentation.spark;

import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTraceCollector;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.PathwayContext;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.apache.spark.SparkConf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OpenlineageParentContext implements AgentSpan.Context {
  private static final Logger log = LoggerFactory.getLogger(OpenlineageParentContext.class);
  private static final Pattern UUID =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

  private final DDTraceId traceId;
  private final long spanId;
  private final long childRootSpanId;

  private final String parentJobNamespace;
  private final String parentJobName;
  private final String parentRunId;

  public static final String OPENLINEAGE_PARENT_JOB_NAMESPACE =
      "spark.openlineage.parentJobNamespace";
  public static final String OPENLINEAGE_PARENT_JOB_NAME = "spark.openlineage.parentJobName";
  public static final String OPENLINEAGE_PARENT_RUN_ID = "spark.openlineage.parentRunId";

  public static Optional<OpenlineageParentContext> from(SparkConf sparkConf) {
    if (!sparkConf.contains(OPENLINEAGE_PARENT_JOB_NAMESPACE)
        || !sparkConf.contains(OPENLINEAGE_PARENT_JOB_NAME)
        || !sparkConf.contains(OPENLINEAGE_PARENT_RUN_ID)) {
      return Optional.empty();
    }

    String parentJobNamespace = sparkConf.get(OPENLINEAGE_PARENT_JOB_NAMESPACE);
    String parentJobName = sparkConf.get(OPENLINEAGE_PARENT_JOB_NAME);
    String parentRunId = sparkConf.get(OPENLINEAGE_PARENT_RUN_ID);

    if (!UUID.matcher(parentRunId).matches()) {
      return Optional.empty();
    }

    return Optional.of(
        new OpenlineageParentContext(parentJobNamespace, parentJobName, parentRunId));
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
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      log.debug("Unable to find SHA-256 algorithm", e);
    }

    if (digest != null && parentJobNamespace != null && parentRunId != null) {
      traceId = computeTraceId(digest, parentJobNamespace, parentJobName, parentRunId);
      spanId = DDSpanId.ZERO;

      childRootSpanId =
          computeChildRootSpanId(digest, parentJobNamespace, parentJobName, parentRunId);
    } else {
      traceId = DDTraceId.ZERO;
      spanId = DDSpanId.ZERO;

      childRootSpanId = DDSpanId.ZERO;
    }

    log.debug("Created OpenlineageParentContext with traceId: {}, spanId: {}", traceId, spanId);
  }

  private long computeChildRootSpanId(
      MessageDigest digest, String parentJobNamespace, String parentJobName, String parentRunId) {
    byte[] inputBytes =
        (parentJobNamespace + parentJobName + parentRunId).getBytes(StandardCharsets.UTF_8);
    byte[] hash = digest.digest(inputBytes);

    return ByteBuffer.wrap(hash).getLong();
  }

  private DDTraceId computeTraceId(
      MessageDigest digest, String parentJobNamespace, String parentJobName, String parentRunId) {
    byte[] inputBytes =
        (parentJobNamespace + parentJobName + parentRunId).getBytes(StandardCharsets.UTF_8);
    byte[] hash = digest.digest(inputBytes);

    return DDTraceId.from(ByteBuffer.wrap(hash).getLong());
  }

  @Override
  public DDTraceId getTraceId() {
    return traceId;
  }

  @Override
  public long getSpanId() {
    return spanId;
  }

  public long getChildRootSpanId() {
    return childRootSpanId;
  }

  @Override
  public AgentTraceCollector getTraceCollector() {
    return AgentTracer.NoopAgentTraceCollector.INSTANCE;
  }

  @Override
  public int getSamplingPriority() {
    return PrioritySampling.USER_KEEP;
  }

  @Override
  public Iterable<Map.Entry<String, String>> baggageItems() {
    return Collections.<String, String>emptyMap().entrySet();
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
