package datadog.trace.instrumentation.spark;

import datadog.trace.api.Config;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.datastreams.PathwayContext;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTraceCollector;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.util.FNV64Hash;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.apache.spark.SparkConf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OpenlineageParentContext implements AgentSpanContext {
  private static final Logger log = LoggerFactory.getLogger(OpenlineageParentContext.class);
  private static final Pattern UUID =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

  private final DDTraceId traceId;
  private final long spanId;

  private final String parentJobNamespace;
  private final String parentJobName;
  private final String parentRunId;
  private final String rootParentJobNamespace;
  private final String rootParentJobName;
  private final String rootParentRunId;

  public static final String OPENLINEAGE_PARENT_JOB_NAMESPACE =
      "spark.openlineage.parentJobNamespace";
  public static final String OPENLINEAGE_PARENT_JOB_NAME = "spark.openlineage.parentJobName";
  public static final String OPENLINEAGE_PARENT_RUN_ID = "spark.openlineage.parentRunId";
  public static final String OPENLINEAGE_ROOT_PARENT_JOB_NAMESPACE =
      "spark.openlineage.rootParentJobNamespace";
  public static final String OPENLINEAGE_ROOT_PARENT_JOB_NAME =
      "spark.openlineage.rootParentJobName";
  public static final String OPENLINEAGE_ROOT_PARENT_RUN_ID = "spark.openlineage.rootParentRunId";

  public static Optional<OpenlineageParentContext> from(SparkConf sparkConf) {
    if (!Config.get().isDataJobsOpenLineageEnabled()) {
      log.debug(
          "OpenLineage - Data Jobs integration disabled. Not returning OpenlineageParentContext");
      return Optional.empty();
    }
    if (!sparkConf.contains(OPENLINEAGE_PARENT_JOB_NAMESPACE)
        || !sparkConf.contains(OPENLINEAGE_PARENT_JOB_NAME)
        || !sparkConf.contains(OPENLINEAGE_PARENT_RUN_ID)) {
      return Optional.empty();
    }

    if (!sparkConf.contains(OPENLINEAGE_ROOT_PARENT_RUN_ID)) {
      log.debug("Found parent info, but not root parent info. Can't construct valid trace id.");
      return Optional.empty();
    }

    String parentJobNamespace = sparkConf.get(OPENLINEAGE_PARENT_JOB_NAMESPACE);
    String parentJobName = sparkConf.get(OPENLINEAGE_PARENT_JOB_NAME);
    String parentRunId = sparkConf.get(OPENLINEAGE_PARENT_RUN_ID);

    if (!UUID.matcher(parentRunId).matches()) {
      log.debug("OpenLineage parent run id is not a valid UUID: {}", parentRunId);
      return Optional.empty();
    }

    String rootParentJobNamespace = sparkConf.get(OPENLINEAGE_ROOT_PARENT_JOB_NAMESPACE);
    String rootParentJobName = sparkConf.get(OPENLINEAGE_ROOT_PARENT_JOB_NAME);
    String rootParentRunId = sparkConf.get(OPENLINEAGE_ROOT_PARENT_RUN_ID);

    if (!UUID.matcher(rootParentRunId).matches()) {
      log.debug("OpenLineage root parent run id is not a valid UUID: {}", parentRunId);
      return Optional.empty();
    }

    return Optional.of(
        new OpenlineageParentContext(
            parentJobNamespace,
            parentJobName,
            parentRunId,
            rootParentJobNamespace,
            rootParentJobName,
            rootParentRunId));
  }

  OpenlineageParentContext(
      String parentJobNamespace,
      String parentJobName,
      String parentRunId,
      String rootParentJobNamespace,
      String rootParentJobName,
      String rootParentRunId) {
    log.debug(
        "Creating OpenlineageParentContext with parentJobNamespace: {}, parentJobName: {}, parentRunId: {}, rootParentJobNamespace: {}, rootParentJobName: {}, rootParentRunId: {}",
        parentJobNamespace,
        parentJobName,
        parentRunId,
        rootParentJobNamespace,
        rootParentJobName,
        rootParentRunId);

    this.parentJobNamespace = parentJobNamespace;
    this.parentJobName = parentJobName;
    this.parentRunId = parentRunId;

    this.rootParentJobNamespace = rootParentJobNamespace;
    this.rootParentJobName = rootParentJobName;
    this.rootParentRunId = rootParentRunId;

    if (this.rootParentRunId != null) {
      traceId = computeTraceId(this.rootParentRunId);
      spanId = computeSpanId(this.parentRunId);
    } else if (this.parentRunId != null) {
      traceId = computeTraceId(this.parentRunId);
      spanId = computeSpanId(this.parentRunId);
    } else {
      traceId = DDTraceId.ZERO;
      spanId = DDSpanId.ZERO;
    }

    log.debug("Created OpenlineageParentContext with traceId: {}, spanId: {}", traceId, spanId);
  }

  private long computeSpanId(String runId) {
    return FNV64Hash.generateHash(runId, FNV64Hash.Version.v1A);
  }

  private DDTraceId computeTraceId(String runId) {
    log.debug("Generating traceID from runId: {}", runId);
    return DDTraceId.from(FNV64Hash.generateHash(runId, FNV64Hash.Version.v1A));
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

  @Override
  public boolean isRemote() {
    return false;
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

  public String getRootParentJobNamespace() {
    return rootParentJobNamespace;
  }

  public String getRootParentJobName() {
    return rootParentJobName;
  }

  public String getRootParentRunId() {
    return rootParentRunId;
  }
}
