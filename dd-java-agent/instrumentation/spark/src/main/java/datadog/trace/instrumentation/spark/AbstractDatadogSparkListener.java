package datadog.trace.instrumentation.spark;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.traceConfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.ProcessTags;
import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.bootstrap.InstanceStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.SpanLink;
import datadog.trace.util.AgentThreadFactory;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Consumer;
import org.apache.spark.ExceptionFailure;
import org.apache.spark.SparkConf;
import org.apache.spark.TaskFailedReason;
import org.apache.spark.scheduler.AccumulableInfo;
import org.apache.spark.scheduler.JobFailed;
import org.apache.spark.scheduler.SparkListener;
import org.apache.spark.scheduler.SparkListenerApplicationEnd;
import org.apache.spark.scheduler.SparkListenerApplicationStart;
import org.apache.spark.scheduler.SparkListenerEvent;
import org.apache.spark.scheduler.SparkListenerExecutorAdded;
import org.apache.spark.scheduler.SparkListenerExecutorRemoved;
import org.apache.spark.scheduler.SparkListenerInterface;
import org.apache.spark.scheduler.SparkListenerJobEnd;
import org.apache.spark.scheduler.SparkListenerJobStart;
import org.apache.spark.scheduler.SparkListenerStageCompleted;
import org.apache.spark.scheduler.SparkListenerStageSubmitted;
import org.apache.spark.scheduler.SparkListenerTaskEnd;
import org.apache.spark.scheduler.StageInfo;
import org.apache.spark.sql.execution.SQLExecution;
import org.apache.spark.sql.execution.SparkPlanInfo;
import org.apache.spark.sql.execution.metric.SQLMetricInfo;
import org.apache.spark.sql.execution.streaming.MicroBatchExecution;
import org.apache.spark.sql.execution.streaming.StreamExecution;
import org.apache.spark.sql.execution.ui.SparkListenerSQLExecutionEnd;
import org.apache.spark.sql.execution.ui.SparkListenerSQLExecutionStart;
import org.apache.spark.sql.streaming.SourceProgress;
import org.apache.spark.sql.streaming.StateOperatorProgress;
import org.apache.spark.sql.streaming.StreamingQueryListener;
import org.apache.spark.sql.streaming.StreamingQueryProgress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Tuple2;
import scala.collection.JavaConverters;

/**
 * Implementation of the SparkListener {@link SparkListener} to generate spans from the execution of
 * a spark application.
 *
 * <p>All the callbacks are called inside the spark driver and in the same thread, but since some
 * methods (like finishApplication) are called from the instrumentation advice, thread-safety is
 * still needed
 */
public abstract class AbstractDatadogSparkListener extends SparkListener {
  private static final Logger log = LoggerFactory.getLogger(AbstractDatadogSparkListener.class);
  protected static final ObjectMapper objectMapper = new ObjectMapper();
  public static volatile AbstractDatadogSparkListener listener = null;

  public static volatile boolean finishTraceOnApplicationEnd = true;
  public static volatile boolean isPysparkShell = false;

  private final int MAX_COLLECTION_SIZE = 5000;
  private final int MAX_ACCUMULATOR_SIZE = 50000;
  private final String RUNTIME_TAGS_PREFIX = "spark.datadog.tags.";
  private static final String AGENT_OL_ENDPOINT = "openlineage/api/v1/lineage";
  private static final int OL_CIRCUIT_BREAKER_TIMEOUT_IN_SECONDS = 60;

  volatile SparkListenerInterface openLineageSparkListener = null;
  public volatile SparkConf openLineageSparkConf = null;

  private final SparkConf sparkConf;
  private final String sparkVersion;
  private final String appId;

  private final AgentTracer.TracerAPI tracer;

  // This is created by constructor, and used if we're not in other known
  // parent context like Databricks, OpenLineage
  private final PredeterminedTraceIdContext predeterminedTraceIdContext;

  private AgentSpan applicationSpan;
  private SparkListenerApplicationStart applicationStart;
  private final HashMap<String, AgentSpan> streamingBatchSpans = new HashMap<>();
  private final HashMap<Long, AgentSpan> sqlSpans = new HashMap<>();
  private final HashMap<Integer, AgentSpan> jobSpans = new HashMap<>();
  private final HashMap<Long, AgentSpan> stageSpans = new HashMap<>();

  private final HashMap<Integer, Integer> stageToJob = new HashMap<>();
  private final HashMap<Long, Properties> stageProperties = new HashMap<>();

  private final SparkAggregatedTaskMetrics applicationMetrics = new SparkAggregatedTaskMetrics();
  private final HashMap<String, SparkAggregatedTaskMetrics> streamingBatchMetrics = new HashMap<>();
  private final HashMap<Long, SparkAggregatedTaskMetrics> sqlMetrics = new HashMap<>();
  private final HashMap<Integer, SparkAggregatedTaskMetrics> jobMetrics = new HashMap<>();
  private final HashMap<Long, SparkAggregatedTaskMetrics> stageMetrics = new HashMap<>();

  private final HashMap<UUID, StreamingQueryListener.QueryStartedEvent> streamingQueries =
      new HashMap<>();
  private final HashMap<Long, SparkListenerSQLExecutionStart> sqlQueries = new HashMap<>();
  protected final HashMap<Long, SparkPlanInfo> sqlPlans = new HashMap<>();
  private final HashMap<String, SparkListenerExecutorAdded> liveExecutors = new HashMap<>();

  // There is no easy way to know if an accumulator is not useful anymore (meaning it is not part of
  // an active SQL query)
  // so capping the size of the collection storing them
  private final Map<Long, SparkSQLUtils.AccumulatorWithStage> accumulators =
      new RemoveEldestHashMap<>(MAX_ACCUMULATOR_SIZE);

  private volatile boolean isStreamingJob = false;
  private final boolean isRunningOnDatabricks;
  private final String databricksClusterName;
  private final String databricksServiceName;
  private final String sparkServiceName;

  private boolean lastJobFailed = false;
  private String lastJobFailedMessage;
  private String lastJobFailedStackTrace;
  private int jobCount = 0;
  private int currentExecutorCount = 0;
  private int maxExecutorCount = 0;
  private long availableExecutorTime = 0;

  private volatile boolean applicationEnded = false;

  public AbstractDatadogSparkListener(SparkConf sparkConf, String appId, String sparkVersion) {
    tracer = AgentTracer.get();

    this.sparkConf = sparkConf;
    this.appId = appId;
    this.sparkVersion = sparkVersion;

    isRunningOnDatabricks = sparkConf.contains("spark.databricks.sparkContextId");
    databricksClusterName = sparkConf.get("spark.databricks.clusterUsageTags.clusterName", null);
    databricksServiceName = getDatabricksServiceName(sparkConf, databricksClusterName);
    sparkServiceName = getSparkServiceName(sparkConf, isRunningOnDatabricks);
    predeterminedTraceIdContext =
        new PredeterminedTraceIdContext(Config.get().getIdGenerationStrategy().generateTraceId());

    // If JVM exiting with System.exit(code), it bypass the code closing the application span
    //
    // Using shutdown hook to close the span, but it is only best effort:
    // - no guarantee it will be executed before the tracer shuts down
    // - no access to the exit code
    Runtime.getRuntime()
        .addShutdownHook(
            AgentThreadFactory.newAgentThread(
                AgentThreadFactory.AgentThread.DATA_JOBS_MONITORING_SHUTDOWN_HOOK,
                () -> {
                  if (!applicationEnded) {
                    log.info("Finishing application trace from shutdown hook");
                    finishApplication(System.currentTimeMillis(), null, 0, null);
                  }
                }));
  }

  public void setupOpenLineage(DDTraceId traceId) {
    log.debug("Setting up OpenLineage configuration with trace id {}", traceId);
    if (openLineageSparkListener != null) {
      openLineageSparkConf.set("spark.openlineage.transport.type", "composite");
      openLineageSparkConf.set("spark.openlineage.transport.continueOnFailure", "true");
      openLineageSparkConf.set("spark.openlineage.transport.transports.agent.type", "http");
      openLineageSparkConf.set(
          "spark.openlineage.transport.transports.agent.url", getAgentHttpUrl());
      openLineageSparkConf.set(
          "spark.openlineage.transport.transports.agent.endpoint", AGENT_OL_ENDPOINT);
      openLineageSparkConf.set("spark.openlineage.transport.transports.agent.compression", "gzip");
      openLineageSparkConf.set(
          "spark.openlineage.run.tags",
          "_dd.trace_id:"
              + traceId.toString()
              + ";_dd.ol_intake.emit_spans:false;_dd.ol_service:"
              + getServiceForOpenLineage(sparkConf, isRunningOnDatabricks)
              + ";_dd.ol_intake.process_tags:"
              + ProcessTags.getTagsForSerialization()
              + ";_dd.ol_app_id:"
              + appId);
      setupOpenLineageCircuitBreaker();
      return;
    }
    log.debug(
        "There is no OpenLineage Spark Listener in the context. Skipping setting tags. {}",
        openLineageSparkListener);
    log.debug(
        "There is no OpenLineage SparkConf in the context. Skipping setting tags. {}",
        openLineageSparkConf);
  }

  /** Resource name of the spark job. Provide an implementation based on a specific scala version */
  protected abstract String getSparkJobName(SparkListenerJobStart jobStart);

  /** Stage ids of the spark job. Provide an implementation based on a specific scala version */
  protected abstract ArrayList<Integer> getSparkJobStageIds(SparkListenerJobStart jobStart);

  /** Stage count of the spark job. Provide an implementation based on a specific scala version */
  protected abstract int getStageCount(SparkListenerJobStart jobStart);

  /** Children of a SparkPlanInfo. Provide an implementation based on a specific scala version */
  protected abstract Collection<SparkPlanInfo> getPlanInfoChildren(SparkPlanInfo info);

  /** Metrics of a SparkPlanInfo. Provide an implementation based on a specific scala version */
  protected abstract List<SQLMetricInfo> getPlanInfoMetrics(SparkPlanInfo info);

  /** Parent Ids of a Stage. Provide an implementation based on a specific scala version */
  protected abstract int[] getStageParentIds(StageInfo info);

  @Override
  public synchronized void onApplicationStart(SparkListenerApplicationStart applicationStart) {
    this.applicationStart = applicationStart;

    if (openLineageSparkListener == null) {
      openLineageSparkListener =
          InstanceStore.of(SparkListenerInterface.class).get("openLineageListener");
      openLineageSparkConf = InstanceStore.of(SparkConf.class).get("openLineageSparkConf");
    }

    if (openLineageSparkListener != null) {
      setupOpenLineage(
          OpenlineageParentContext.from(sparkConf)
              .map(context -> context.getTraceId())
              .orElse(predeterminedTraceIdContext.getTraceId()));
    }
    notifyOl(x -> openLineageSparkListener.onApplicationStart(x), applicationStart);
  }

  private void initApplicationSpanIfNotInitialized() {
    if (applicationSpan != null) {
      return;
    }

    log.debug("Starting tracer application span.");

    AgentTracer.SpanBuilder builder = buildSparkSpan("spark.application", null);

    if (applicationStart != null) {
      builder
          .withStartTimestamp(applicationStart.time() * 1000)
          .withTag("application_name", applicationStart.appName())
          .withTag("spark_user", applicationStart.sparkUser());

      if (applicationStart.appAttemptId().isDefined()) {
        builder.withTag("app_attempt_id", applicationStart.appAttemptId().get());
      }
    }

    captureApplicationParameters(builder);

    Optional<OpenlineageParentContext> openlineageParentContext =
        OpenlineageParentContext.from(sparkConf);
    // We know we're not in Databricks context
    if (openlineageParentContext.isPresent()) {
      captureOpenlineageContextIfPresent(builder, openlineageParentContext.get());
    } else {
      builder.asChildOf(predeterminedTraceIdContext);
    }

    applicationSpan = builder.start();
    setDataJobsSamplingPriority(applicationSpan);
    applicationSpan.setMeasured(true);
  }

  private void captureOpenlineageContextIfPresent(
      AgentTracer.SpanBuilder builder, OpenlineageParentContext context) {
    builder.asChildOf(context);

    log.debug("Captured Openlineage context: {}, with trace_id: {}", context, context.getTraceId());

    builder.withTag("openlineage_parent_job_namespace", context.getParentJobNamespace());
    builder.withTag("openlineage_parent_job_name", context.getParentJobName());
    builder.withTag("openlineage_parent_run_id", context.getParentRunId());
    builder.withTag("openlineage_root_parent_job_namespace", context.getRootParentJobNamespace());
    builder.withTag("openlineage_root_parent_job_name", context.getRootParentJobName());
    builder.withTag("openlineage_root_parent_run_id", context.getRootParentRunId());
  }

  @Override
  public void onApplicationEnd(SparkListenerApplicationEnd applicationEnd) {
    log.info(
        "Received spark application end event, finish trace on this event: {}",
        finishTraceOnApplicationEnd);
    notifyOl(x -> openLineageSparkListener.onApplicationEnd(x), applicationEnd);

    if (finishTraceOnApplicationEnd) {
      finishApplication(applicationEnd.time(), null, 0, null);
    }
  }

  // This function is called using reflection in SparkExitInstrumentation, make sure to update if
  // the signature of this function is changed
  public synchronized void finishApplication(
      long time, Throwable throwable, int exitCode, String msg) {
    log.info("Finishing spark application trace");

    if (applicationEnded) {
      return;
    }
    applicationEnded = true;

    if (applicationSpan == null && jobCount > 0) {
      // If the application span is not initialized, but spark jobs have been executed, all those
      // spark jobs were databricks or streaming. In this case we don't send the application span
      return;
    }
    initApplicationSpanIfNotInitialized();

    if (throwable != null) {
      applicationSpan.addThrowable(throwable);
    } else if (exitCode != 0) {
      applicationSpan.setError(true);
      applicationSpan.setTag(
          DDTags.ERROR_TYPE, "Spark Application Failed with exit code " + exitCode);

      String errorMessage = getErrorMessageWithoutStackTrace(msg);
      applicationSpan.setTag(DDTags.ERROR_MSG, errorMessage);
      applicationSpan.setTag(DDTags.ERROR_STACK, msg);
    } else if (lastJobFailed) {
      applicationSpan.setError(true);
      applicationSpan.setTag(DDTags.ERROR_TYPE, "Spark Application Failed");
      applicationSpan.setTag(DDTags.ERROR_MSG, lastJobFailedMessage);
      applicationSpan.setTag(DDTags.ERROR_STACK, lastJobFailedStackTrace);
    }

    applicationMetrics.setSpanMetrics(applicationSpan);
    applicationSpan.setMetric("spark.max_executor_count", maxExecutorCount);
    applicationSpan.setMetric(
        "spark.available_executor_time", computeCurrentAvailableExecutorTime(time));

    applicationSpan.finish(time * 1000);

    // write traces synchronously:
    // as soon as the application finishes, the JVM starts to shut down
    tracer.flush();
  }

  private AgentSpan getOrCreateStreamingBatchSpan(
      String batchKey, Long timeMs, Properties jobProperties) {
    AgentSpan batchSpan = streamingBatchSpans.get(batchKey);
    if (batchSpan != null) {
      return batchSpan;
    }

    AgentTracer.SpanBuilder builder =
        buildSparkSpan("spark.streaming_batch", jobProperties).withStartTimestamp(timeMs * 1000);

    // Streaming spans will always be the root span, capturing job parameters on those
    captureJobParameters(builder, jobProperties);

    if (isRunningOnDatabricks) {
      addDatabricksSpecificTags(builder, jobProperties, false);
    }

    batchSpan = builder.start();
    setDataJobsSamplingPriority(batchSpan);
    streamingBatchSpans.put(batchKey, batchSpan);
    return batchSpan;
  }

  private void addDatabricksSpecificTags(
      AgentTracer.SpanBuilder builder, Properties properties, boolean withParentContext) {
    // Databricks has no application span. Adding the parameters to the top level spark span
    captureJobParameters(builder, properties);

    if (properties != null) {
      String databricksJobId = getDatabricksJobId(properties);
      String databricksJobRunId = getDatabricksJobRunId(properties, databricksClusterName);
      String databricksTaskRunId = getDatabricksTaskRunId(properties);

      // ids to link those spans to databricks job/task traces
      builder.withTag("databricks_job_id", databricksJobId);
      builder.withTag("databricks_job_run_id", databricksJobRunId);
      builder.withTag("databricks_task_run_id", databricksTaskRunId);

      AgentSpanContext parentContext =
          new DatabricksParentContext(databricksJobId, databricksJobRunId, databricksTaskRunId);

      if (parentContext.getTraceId() != DDTraceId.ZERO) {
        if (withParentContext) {
          builder.asChildOf(parentContext);
        } else {
          builder.withLink(SpanLink.from(parentContext));
        }
      }
    }
  }

  private AgentSpan getOrCreateSqlSpan(
      long sqlExecutionId, String batchKey, Properties jobProperties) {
    AgentSpan span = sqlSpans.get(sqlExecutionId);
    if (span != null) {
      return span;
    }

    SparkListenerSQLExecutionStart queryStart = sqlQueries.get(sqlExecutionId);
    if (queryStart == null) {
      return null;
    }

    AgentTracer.SpanBuilder spanBuilder =
        buildSparkSpan("spark.sql", jobProperties)
            .withStartTimestamp(queryStart.time() * 1000)
            .withTag("query_id", sqlExecutionId)
            .withTag("description", queryStart.description())
            .withTag("details", queryStart.details())
            .withTag("_dd.spark.physical_plan", queryStart.physicalPlanDescription())
            .withTag(DDTags.RESOURCE_NAME, queryStart.description());

    if (batchKey != null) {
      AgentSpan batchSpan =
          getOrCreateStreamingBatchSpan(batchKey, queryStart.time(), jobProperties);
      spanBuilder.asChildOf(batchSpan.context());
    } else if (isRunningOnDatabricks) {
      addDatabricksSpecificTags(spanBuilder, jobProperties, true);
    } else {
      initApplicationSpanIfNotInitialized();
      spanBuilder.asChildOf(applicationSpan.context());
    }

    AgentSpan sqlSpan = spanBuilder.start();
    setDataJobsSamplingPriority(sqlSpan);
    sqlSpans.put(sqlExecutionId, sqlSpan);
    return sqlSpan;
  }

  @Override
  public synchronized void onJobStart(SparkListenerJobStart jobStart) {
    jobCount++;
    if (jobSpans.size() > MAX_COLLECTION_SIZE) {
      return;
    }

    AgentTracer.SpanBuilder jobSpanBuilder =
        buildSparkSpan("spark.job", jobStart.properties())
            .withStartTimestamp(jobStart.time() * 1000)
            .withTag("job_id", jobStart.jobId())
            .withTag("stage_count", getStageCount(jobStart));

    String batchKey = getStreamingBatchKey(jobStart.properties());
    Long sqlExecutionId = getSqlExecutionId(jobStart.properties());
    AgentSpan sqlSpan = null;

    if (sqlExecutionId != null) {
      sqlSpan = getOrCreateSqlSpan(sqlExecutionId, batchKey, jobStart.properties());
    }

    /*-
     * The spark.job span hierarchy depends on the setup:
     *
     * spark.application | spark.streaming_batch | databricks.task depending on the environment where spark is running
     *               \          |                   /
     *                    [spark.sql] optional, only present if using spark-sql
     *                          |
     *                      spark.job
     */
    if (sqlSpan != null) {
      jobSpanBuilder.asChildOf(sqlSpan.context());
    } else if (batchKey != null) {
      isStreamingJob = true;
      AgentSpan batchSpan =
          getOrCreateStreamingBatchSpan(batchKey, jobStart.time(), jobStart.properties());
      jobSpanBuilder.asChildOf(batchSpan.context());
    } else if (isRunningOnDatabricks) {
      addDatabricksSpecificTags(jobSpanBuilder, jobStart.properties(), true);
    } else {
      // In non-databricks, non-streaming env, the spark application is the local root span
      initApplicationSpanIfNotInitialized();
      jobSpanBuilder.asChildOf(applicationSpan.context());
    }

    jobSpanBuilder.withTag(DDTags.RESOURCE_NAME, getSparkJobName(jobStart));

    AgentSpan jobSpan = jobSpanBuilder.start();
    setDataJobsSamplingPriority(jobSpan);
    jobSpan.setMeasured(true);

    for (int stageId : getSparkJobStageIds(jobStart)) {
      stageToJob.put(stageId, jobStart.jobId());
    }
    jobSpans.put(jobStart.jobId(), jobSpan);
    notifyOl(x -> openLineageSparkListener.onJobStart(x), jobStart);
  }

  @Override
  public synchronized void onJobEnd(SparkListenerJobEnd jobEnd) {
    AgentSpan jobSpan = jobSpans.remove(jobEnd.jobId());
    if (jobSpan == null) {
      return;
    }

    if (jobEnd.jobResult() instanceof JobFailed) {
      JobFailed jobFailed = (JobFailed) jobEnd.jobResult();
      Exception exception = jobFailed.exception();

      String errorMessage = getErrorMessageWithoutStackTrace(exception.getMessage());
      String errorStackTrace = stackTraceToString(exception);

      jobSpan.setError(true);
      jobSpan.setErrorMessage(errorMessage);
      jobSpan.setTag(DDTags.ERROR_STACK, errorStackTrace);
      jobSpan.setTag(DDTags.ERROR_TYPE, "Spark Job Failed");

      // Only propagate the error to the application if it is not a cancellation
      if (errorMessage != null && !errorMessage.toLowerCase().contains("cancelled")) {
        lastJobFailed = true;
        lastJobFailedMessage = errorMessage;
        lastJobFailedStackTrace = errorStackTrace;
      }
    } else {
      lastJobFailed = false;
    }

    SparkAggregatedTaskMetrics metrics = jobMetrics.remove(jobEnd.jobId());
    if (metrics != null) {
      metrics.setSpanMetrics(jobSpan);
    }
    notifyOl(x -> openLineageSparkListener.onJobEnd(x), jobEnd);

    jobSpan.finish(jobEnd.time() * 1000);
  }

  @Override
  public synchronized void onStageSubmitted(SparkListenerStageSubmitted stageSubmitted) {
    if (stageSpans.size() > MAX_COLLECTION_SIZE) {
      return;
    }

    int stageId = stageSubmitted.stageInfo().stageId();
    int stageAttemptId = stageSubmitted.stageInfo().attemptNumber();

    Integer jobId = stageToJob.get(stageId);
    if (jobId == null) {
      return;
    }

    AgentSpan jobSpan = jobSpans.get(jobId);
    if (jobSpan == null) {
      return;
    }

    long submissionTimeMs;
    if (stageSubmitted.stageInfo().submissionTime().isDefined()) {
      submissionTimeMs = (long) stageSubmitted.stageInfo().submissionTime().get();
    } else {
      submissionTimeMs = System.currentTimeMillis();
    }

    long stageSpanKey = stageSpanKey(stageId, stageAttemptId);
    stageMetrics.put(
        stageSpanKey,
        new SparkAggregatedTaskMetrics(computeCurrentAvailableExecutorTime(submissionTimeMs)));
    stageProperties.put(stageSpanKey, stageSubmitted.properties());

    AgentSpan stageSpan =
        buildSparkSpan("spark.stage", stageSubmitted.properties())
            .asChildOf(jobSpan.context())
            .withStartTimestamp(submissionTimeMs * 1000)
            .withTag("stage_id", stageId)
            .withTag(
                "parent_stage_ids", Arrays.toString(getStageParentIds(stageSubmitted.stageInfo())))
            .withTag("task_count", stageSubmitted.stageInfo().numTasks())
            .withTag("attempt_id", stageAttemptId)
            .withTag(DDTags.RESOURCE_NAME, stageSubmitted.stageInfo().name())
            .start();

    setDataJobsSamplingPriority(stageSpan);
    stageSpan.setMeasured(true);

    stageSpans.put(stageSpanKey(stageId, stageAttemptId), stageSpan);
  }

  @Override
  public synchronized void onStageCompleted(SparkListenerStageCompleted stageCompleted) {
    StageInfo stageInfo = stageCompleted.stageInfo();
    int stageId = stageInfo.stageId();
    int stageAttemptId = stageInfo.attemptNumber();

    Integer jobId = stageToJob.get(stageId);
    if (jobId == null) {
      return;
    }

    long stageSpanKey = stageSpanKey(stageId, stageAttemptId);
    AgentSpan span = stageSpans.remove(stageSpanKey);
    if (span == null) {
      return;
    }

    span.setTag("details", stageCompleted.stageInfo().details());
    if (stageInfo.failureReason().isDefined()) {
      span.setError(true);
      span.setErrorMessage(getErrorMessageWithoutStackTrace(stageInfo.failureReason().get()));
      span.setTag(DDTags.ERROR_STACK, stageInfo.failureReason().get());
      span.setTag(DDTags.ERROR_TYPE, "Spark Stage Failed");
    }

    long completionTimeMs;
    if (stageCompleted.stageInfo().completionTime().isDefined()) {
      completionTimeMs = (long) stageCompleted.stageInfo().completionTime().get();
    } else {
      completionTimeMs = System.currentTimeMillis();
    }

    long currentAvailableExecutorTime = computeCurrentAvailableExecutorTime(completionTimeMs);
    for (SparkAggregatedTaskMetrics metric : stageMetrics.values()) {
      metric.allocateAvailableExecutorTime(currentAvailableExecutorTime);
    }

    for (AccumulableInfo info :
        JavaConverters.asJavaCollection(stageInfo.accumulables().values())) {
      accumulators.put(info.id(), new SparkSQLUtils.AccumulatorWithStage(stageId, info));
    }

    Properties prop = stageProperties.remove(stageSpanKey);
    Long sqlExecutionId = getSqlExecutionId(prop);

    SparkAggregatedTaskMetrics stageMetric = stageMetrics.remove(stageSpanKey);
    if (stageMetric != null) {
      stageMetric.computeSkew();
      stageMetric.setSpanMetrics(span);
      applicationMetrics.accumulateStageMetrics(stageMetric);

      jobMetrics
          .computeIfAbsent(jobId, k -> new SparkAggregatedTaskMetrics())
          .accumulateStageMetrics(stageMetric);

      String batchKey = getStreamingBatchKey(prop);
      if (batchKey != null) {
        streamingBatchMetrics
            .computeIfAbsent(batchKey, k -> new SparkAggregatedTaskMetrics())
            .accumulateStageMetrics(stageMetric);
      }

      if (sqlExecutionId != null) {
        sqlMetrics
            .computeIfAbsent(sqlExecutionId, k -> new SparkAggregatedTaskMetrics())
            .accumulateStageMetrics(stageMetric);
      }
    }

    SparkPlanInfo sqlPlan = sqlPlans.get(sqlExecutionId);
    if (sqlPlan != null) {
      SparkSQLUtils.addSQLPlanToStageSpan(span, sqlPlan, accumulators, stageId);
    }

    span.finish(completionTimeMs * 1000);
  }

  @Override
  public void onTaskEnd(SparkListenerTaskEnd taskEnd) {
    int stageId = taskEnd.stageId();
    int stageAttemptId = taskEnd.stageAttemptId();
    long stageSpanKey = stageSpanKey(stageId, stageAttemptId);

    SparkAggregatedTaskMetrics stageMetric = stageMetrics.get(stageSpanKey);
    if (stageMetric != null) {
      stageMetric.addTaskMetrics(taskEnd);
    }

    if (taskEnd.taskMetrics() != null) {
      // Record the runtime in each active stage in order to allocate the available executor time
      long taskRunTime = SparkAggregatedTaskMetrics.computeTaskRunTime(taskEnd.taskMetrics());
      for (SparkAggregatedTaskMetrics aggMetrics : stageMetrics.values()) {
        aggMetrics.recordTotalTaskRunTime(taskRunTime);
      }
    }

    // OpenLineage call should be prior to method return statements
    notifyOl(x -> openLineageSparkListener.onTaskEnd(x), taskEnd);

    // Only sending failing tasks
    if (!(taskEnd.reason() instanceof TaskFailedReason)) {
      return;
    }

    // Only sending tasks that can lead to failure
    TaskFailedReason reason = (TaskFailedReason) taskEnd.reason();
    if (!reason.countTowardsTaskFailures()) {
      return;
    }

    AgentSpan stageSpan = stageSpans.get(stageSpanKey);
    if (stageSpan == null) {
      return;
    }

    Properties props = stageProperties.get(stageSpanKey);
    sendTaskSpan(stageSpan, taskEnd, props);
  }

  public static boolean classIsLoadable(String className) {
    try {
      Class.forName(
          className,
          false,
          Optional.ofNullable(Thread.currentThread().getContextClassLoader())
              .orElse(SparkConf.class.getClassLoader()));
      return true;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }

  private void sendTaskSpan(
      AgentSpan stageSpan, SparkListenerTaskEnd taskEnd, Properties properties) {
    AgentSpan taskSpan =
        buildSparkSpan("spark.task", properties)
            .asChildOf(stageSpan.context())
            .withStartTimestamp(taskEnd.taskInfo().launchTime() * 1000)
            .withTag("task_id", taskEnd.taskInfo().taskId())
            .withTag("task_attempt_id", taskEnd.taskInfo().attemptNumber())
            .withTag("task_type", taskEnd.taskType())
            .withTag("stage_id", taskEnd.stageId())
            .withTag("stage_attempt_id", taskEnd.stageAttemptId())
            .withTag("executor_id", taskEnd.taskInfo().executorId())
            .withTag("host", taskEnd.taskInfo().host())
            .withTag("task_locality", taskEnd.taskInfo().taskLocality().toString())
            .withTag("speculative", taskEnd.taskInfo().speculative())
            .withTag("status", taskEnd.taskInfo().status())
            .start();

    if (taskEnd.reason() instanceof TaskFailedReason) {
      TaskFailedReason reason = (TaskFailedReason) taskEnd.reason();

      taskSpan.setError(true);
      taskSpan.setTag(DDTags.ERROR_TYPE, "Spark Task Failed");

      if (reason instanceof ExceptionFailure) {
        ExceptionFailure exceptionFailure = (ExceptionFailure) reason;

        taskSpan.setErrorMessage(
            String.format("%s: %s", exceptionFailure.className(), exceptionFailure.description()));
        taskSpan.setTag(DDTags.ERROR_STACK, exceptionFailure.fullStackTrace());
      } else {
        taskSpan.setErrorMessage(reason.toErrorString());
      }

      taskSpan.setTag("count_towards_task_failures", reason.countTowardsTaskFailures());
    }

    setDataJobsSamplingPriority(taskSpan);
    taskSpan.finish(taskEnd.taskInfo().finishTime() * 1000);
  }

  @Override
  public synchronized void onExecutorAdded(SparkListenerExecutorAdded executorAdded) {
    currentExecutorCount += 1;
    maxExecutorCount = Math.max(maxExecutorCount, currentExecutorCount);

    if (liveExecutors.size() <= MAX_COLLECTION_SIZE) {
      liveExecutors.put(executorAdded.executorId(), executorAdded);
    }
  }

  @Override
  public synchronized void onExecutorRemoved(SparkListenerExecutorRemoved executorRemoved) {
    currentExecutorCount -= 1;

    SparkListenerExecutorAdded executor = liveExecutors.remove(executorRemoved.executorId());
    if (executor != null) {
      availableExecutorTime +=
          (executorRemoved.time() - executor.time()) * executor.executorInfo().totalCores();
    }
  }

  @Override
  public void onOtherEvent(SparkListenerEvent event) {
    if (event instanceof StreamingQueryListener.QueryStartedEvent) {
      onStreamingQueryStartedEvent((StreamingQueryListener.QueryStartedEvent) event);
    } else if (event instanceof StreamingQueryListener.QueryProgressEvent) {
      onStreamingQueryProgressEvent((StreamingQueryListener.QueryProgressEvent) event);
    } else if (event instanceof StreamingQueryListener.QueryTerminatedEvent) {
      onStreamingQueryTerminatedEvent((StreamingQueryListener.QueryTerminatedEvent) event);
    } else if (event instanceof SparkListenerSQLExecutionStart) {
      onSQLExecutionStart((SparkListenerSQLExecutionStart) event);
    } else if (event instanceof SparkListenerSQLExecutionEnd) {
      onSQLExecutionEnd((SparkListenerSQLExecutionEnd) event);
    }

    updateAdaptiveSQLPlan(event);
  }

  private <T extends SparkListenerEvent> void notifyOl(Consumer<T> ol, T event) {
    if (!Config.get().isDataJobsOpenLineageEnabled()) {
      log.debug("Ignoring event {} - OpenLineage not enabled", event);
      return;
    }

    if (isRunningOnDatabricks || isStreamingJob) {
      log.debug("Not emitting event when running on databricks or on streaming jobs");
      return;
    }
    if (openLineageSparkListener != null) {
      log.debug(
          "Passing event `{}` to OpenLineageSparkListener", event.getClass().getCanonicalName());
      ol.accept(event);
    } else {
      log.debug("OpenLineageSparkListener is null");
    }
  }

  private static final Class<?> adaptiveExecutionUpdateClass;
  private static final MethodHandle adaptiveExecutionIdMethod;
  private static final MethodHandle adaptiveSparkPlanMethod;

  @SuppressForbidden // Using reflection to avoid splitting the instrumentation once more
  private static Class<?> findAdaptiveExecutionUpdateClass() throws ClassNotFoundException {
    return Class.forName(
        "org.apache.spark.sql.execution.ui.SparkListenerSQLAdaptiveExecutionUpdate");
  }

  static {
    Class<?> executionUpdateClass = null;
    MethodHandle executionIdMethod = null;
    MethodHandle sparkPlanMethod = null;

    try {
      MethodHandles.Lookup lookup = MethodHandles.lookup();

      executionUpdateClass = findAdaptiveExecutionUpdateClass();
      executionIdMethod =
          lookup.findVirtual(
              executionUpdateClass, "executionId", MethodType.methodType(long.class));
      sparkPlanMethod =
          lookup.findVirtual(
              executionUpdateClass, "sparkPlanInfo", MethodType.methodType(SparkPlanInfo.class));
    } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException ignored) {
    }

    adaptiveExecutionUpdateClass = executionUpdateClass;
    adaptiveExecutionIdMethod = executionIdMethod;
    adaptiveSparkPlanMethod = sparkPlanMethod;
  }

  private synchronized void updateAdaptiveSQLPlan(SparkListenerEvent event) {
    try {
      if (adaptiveExecutionUpdateClass != null && adaptiveExecutionUpdateClass.isInstance(event)) {
        long queryId = (long) adaptiveExecutionIdMethod.invoke(event);
        SparkPlanInfo sparkPlanInfo = (SparkPlanInfo) adaptiveSparkPlanMethod.invoke(event);

        sqlPlans.put(queryId, sparkPlanInfo);
      }
    } catch (Throwable ignored) {
    }
  }

  private synchronized void onSQLExecutionStart(SparkListenerSQLExecutionStart sqlStart) {
    sqlPlans.put(sqlStart.executionId(), sqlStart.sparkPlanInfo());
    sqlQueries.put(sqlStart.executionId(), sqlStart);
    notifyOl(x -> openLineageSparkListener.onOtherEvent(x), sqlStart);
  }

  private synchronized void onSQLExecutionEnd(SparkListenerSQLExecutionEnd sqlEnd) {
    AgentSpan span = sqlSpans.remove(sqlEnd.executionId());
    SparkAggregatedTaskMetrics metrics = sqlMetrics.remove(sqlEnd.executionId());
    sqlQueries.remove(sqlEnd.executionId());
    sqlPlans.remove(sqlEnd.executionId());

    if (span != null) {
      if (metrics != null) {
        metrics.setSpanMetrics(span);
      }
      notifyOl(x -> openLineageSparkListener.onOtherEvent(x), sqlEnd);

      span.finish(sqlEnd.time() * 1000);
    }
  }

  private synchronized void onStreamingQueryStartedEvent(
      StreamingQueryListener.QueryStartedEvent event) {
    if (streamingQueries.size() > MAX_COLLECTION_SIZE) {
      return;
    }

    streamingQueries.put(event.id(), event);
  }

  private synchronized void onStreamingQueryTerminatedEvent(
      StreamingQueryListener.QueryTerminatedEvent event) {
    StreamingQueryListener.QueryStartedEvent startedEvent = streamingQueries.remove(event.id());

    ArrayList<String> batchesToFinish = new ArrayList<>();

    for (String batchKey : streamingBatchSpans.keySet()) {
      if (batchKey.startsWith(event.id().toString())) {
        batchesToFinish.add(batchKey);
      }
    }

    for (String batchKey : batchesToFinish) {
      AgentSpan batchSpan = streamingBatchSpans.remove(batchKey);
      SparkAggregatedTaskMetrics metrics = streamingBatchMetrics.remove(batchKey);

      if (batchSpan != null) {
        if (metrics != null) {
          metrics.setSpanMetrics(batchSpan);
        }

        batchSpan.setTag("streaming_query.id", event.id());
        batchSpan.setTag("streaming_query.run_id", event.runId());
        batchSpan.setTag("streaming_query.batch_id", getBatchIdFromBatchKey(batchKey));
        if (startedEvent != null) {
          batchSpan.setTag("streaming_query.name", startedEvent.name());
          batchSpan.setTag(DDTags.RESOURCE_NAME, startedEvent.name());
        }

        if (event.exception().isDefined()) {
          String exceptionString = event.exception().get();

          batchSpan.setError(true);
          batchSpan.setErrorMessage(getErrorMessageWithoutStackTrace(exceptionString));
          batchSpan.setTag(DDTags.ERROR_STACK, exceptionString);
        }

        batchSpan.finish();
      }
    }
  }

  private synchronized void onStreamingQueryProgressEvent(
      StreamingQueryListener.QueryProgressEvent event) {
    StreamingQueryProgress progress = event.progress();

    String batchKey =
        getStreamingBatchKey(progress.id().toString(), String.valueOf(progress.batchId()));

    AgentSpan batchSpan = streamingBatchSpans.remove(batchKey);
    SparkAggregatedTaskMetrics metrics = streamingBatchMetrics.remove(batchKey);
    if (batchSpan != null) {
      if (metrics != null) {
        metrics.setSpanMetrics(batchSpan);
      }

      batchSpan.setTag("streaming_query.id", progress.id());
      batchSpan.setTag("streaming_query.run_id", progress.runId());
      batchSpan.setTag("streaming_query.batch_id", progress.batchId());
      batchSpan.setTag("streaming_query.name", progress.name());
      batchSpan.setTag(DDTags.RESOURCE_NAME, progress.name());

      batchSpan.setMetric("spark.num_input_rows", progress.numInputRows());
      batchSpan.setMetric("spark.input_rows_per_second", progress.inputRowsPerSecond());
      batchSpan.setMetric("spark.processed_rows_per_second", progress.processedRowsPerSecond());

      Long watermark = convertStringDateToMillis(progress.eventTime().get("watermark"));
      if (watermark != null) {
        batchSpan.setMetric("spark.event_time.watermark", watermark);

        Long progressTimestamp = convertStringDateToMillis(progress.timestamp());
        if (watermark > 0 && progressTimestamp != null) {
          batchSpan.setMetric("spark.event_time.watermark_gap", progressTimestamp - watermark);
        }
      }
      Long maxEventTime = convertStringDateToMillis(progress.eventTime().get("max"));
      if (maxEventTime != null) {
        batchSpan.setMetric("spark.event_time.max", maxEventTime);
      }
      Long minEventTime = convertStringDateToMillis(progress.eventTime().get("min"));
      if (minEventTime != null) {
        batchSpan.setMetric("spark.event_time.min", minEventTime);
      }

      Long addBatch = progress.durationMs().get("addBatch");
      if (addBatch != null) {
        batchSpan.setMetric("spark.add_batch_duration", addBatch);
      }
      Long getBatch = progress.durationMs().get("getBatch");
      if (getBatch != null) {
        batchSpan.setMetric("spark.get_batch_duration", getBatch);
      }
      Long latestOffset = progress.durationMs().get("latestOffset");
      if (latestOffset != null) {
        batchSpan.setMetric("spark.latest_offset_duration", latestOffset);
      }
      Long queryPlanning = progress.durationMs().get("queryPlanning");
      if (queryPlanning != null) {
        batchSpan.setMetric("spark.query_planing_duration", queryPlanning);
      }
      Long triggerExecution = progress.durationMs().get("triggerExecution");
      if (triggerExecution != null) {
        batchSpan.setMetric("spark.trigger_execution_duration", triggerExecution);
      }
      Long walCommit = progress.durationMs().get("walCommit");
      if (walCommit != null) {
        batchSpan.setMetric("spark.wal_commit_duration", walCommit);
      }

      for (int i = 0; i < progress.sources().length; i++) {
        SourceProgress source = progress.sources()[i];
        String prefix = "spark.source." + i + ".";

        batchSpan.setTag(prefix + "description", source.description());
        batchSpan.setTag(prefix + "start_offset", source.startOffset());
        batchSpan.setTag(prefix + "end_offset", source.endOffset());
        batchSpan.setTag(prefix + "num_input_rows", source.numInputRows());
        batchSpan.setTag(prefix + "input_rows_per_second", source.inputRowsPerSecond());
        batchSpan.setTag(prefix + "processed_rows_per_second", source.processedRowsPerSecond());

        reportKafkaOffsets(batchSpan.getServiceName(), batchSpan, source);
      }

      for (int i = 0; i < progress.stateOperators().length; i++) {
        StateOperatorProgress state = progress.stateOperators()[i];
        String prefix = "spark.state." + i + ".";

        batchSpan.setTag(prefix + "num_rows_total", state.numRowsTotal());
        batchSpan.setTag(prefix + "num_rows_updated", state.numRowsUpdated());
        batchSpan.setTag(prefix + "memory_used_bytes", state.memoryUsedBytes());
      }

      batchSpan.setTag("spark.sink.description", progress.sink().description());

      batchSpan.finish();
    }
  }

  private void setDataJobsSamplingPriority(AgentSpan span) {
    span.setSamplingPriority(PrioritySampling.USER_KEEP, SamplingMechanism.DATA_JOBS);
  }

  private AgentTracer.SpanBuilder buildSparkSpan(String spanName, Properties properties) {
    AgentTracer.SpanBuilder builder =
        tracer.buildSpan(spanName).withSpanType("spark").withTag("app_id", appId);

    if (databricksServiceName != null) {
      builder.withServiceName(databricksServiceName);
    } else if (sparkServiceName != null) {
      builder.withServiceName(sparkServiceName);
    }

    addPropertiesTags(builder, properties);

    return builder;
  }

  private void addPropertiesTags(AgentTracer.SpanBuilder builder, Properties properties) {
    if (properties == null) {
      return;
    }

    for (String propertyName : properties.stringPropertyNames()) {
      if (propertyName.startsWith(RUNTIME_TAGS_PREFIX)) {
        String value = properties.getProperty(propertyName);
        String key = propertyName.substring(RUNTIME_TAGS_PREFIX.length());

        builder.withTag(key, value);
      }
    }
  }

  private long stageSpanKey(int stageId, int attemptId) {
    return ((long) stageId << 32) + attemptId;
  }

  @SuppressForbidden // split with one-char String use a fast-path without regex usage
  private static String getDatabricksJobId(Properties properties) {
    String jobId = properties.getProperty("spark.databricks.job.id");
    if (jobId != null) {
      return jobId;
    }

    // First fallback, use spark.jobGroup.id with the pattern
    // <scheduler_id>_job-<job_id>-run-<task_run_id>-action-<action_id>
    String jobGroupId = properties.getProperty("spark.jobGroup.id");
    if (jobGroupId != null) {
      int startIndex = jobGroupId.indexOf("job-");
      int endIndex = jobGroupId.indexOf("-run", startIndex);
      if (startIndex != -1 && endIndex != -1) {
        return jobGroupId.substring(startIndex + 4, endIndex);
      }
    }

    // Second fallback, use spark.databricks.workload.id with pattern
    // <org_id>-<job_id>-<task_run_id>
    String workloadId = properties.getProperty("spark.databricks.workload.id");
    if (workloadId != null) {
      String[] parts = workloadId.split("-");
      if (parts.length > 1) {
        return parts[1];
      }
    }

    return null;
  }

  @SuppressForbidden // split with one-char String use a fast-path without regex usage
  private static String getDatabricksJobRunId(
      Properties jobProperties, String databricksClusterName) {
    String jobRunId = jobProperties.getProperty("spark.databricks.job.parentRunId");
    if (jobRunId != null) {
      return jobRunId;
    }

    // Fallback, extract the jobRunId from the cluster name for job clusters having the pattern
    // job-<job_id>-run-<job_run_id>
    String clusterName = jobProperties.getProperty("spark.databricks.clusterUsageTags.clusterName");

    // Using the databricksClusterName as fallback, if not present in jobProperties
    clusterName = (clusterName == null) ? databricksClusterName : clusterName;

    if (clusterName == null) {
      return null;
    }

    // For job cluster, the cluster name has a pattern job-<job_id>-run-<job_run_id>
    String[] parts = clusterName.split("-");
    if (parts.length > 3) {
      return parts[3];
    }

    return null;
  }

  @SuppressForbidden // split with one-char String use a fast-path without regex usage
  private static String getDatabricksTaskRunId(Properties properties) {
    // spark.databricks.job.runId is the runId of the task, not of the Job
    String taskRunId = properties.getProperty("spark.databricks.job.runId");
    if (taskRunId != null) {
      return taskRunId;
    }

    // First fallback, use spark.jobGroup.id with the pattern
    // <scheduler_id>_job-<job_id>-run-<task_run_id>-action-<action_id>
    String jobGroupId = properties.getProperty("spark.jobGroup.id");
    if (jobGroupId != null) {
      int startIndex = jobGroupId.indexOf("run-");
      int endIndex = jobGroupId.indexOf("-action", startIndex);
      if (startIndex != -1 && endIndex != -1) {
        return jobGroupId.substring(startIndex + 4, endIndex);
      }
    }

    // Second fallback, use spark.databricks.workload.id with pattern
    // <org_id>-<job_id>-<task_run_id>
    String workloadId = properties.getProperty("spark.databricks.workload.id");
    if (workloadId != null) {
      String[] parts = workloadId.split("-");
      if (parts.length > 2) {
        return parts[2];
      }
    }

    return null;
  }

  private String stackTraceToString(Throwable e) {
    StringWriter stringWriter = new StringWriter();
    e.printStackTrace(new PrintWriter(stringWriter));
    return stringWriter.toString();
  }

  private String getErrorMessageWithoutStackTrace(String errorMessage) {
    if (errorMessage == null) {
      return null;
    }

    int stackTraceIndex = errorMessage.indexOf("\tat ");
    if (stackTraceIndex != -1) {
      return errorMessage.substring(0, stackTraceIndex);
    }

    return errorMessage;
  }

  private long computeCurrentAvailableExecutorTime(long time) {
    long currentAvailableExecutorTime = availableExecutorTime;

    for (SparkListenerExecutorAdded executor : liveExecutors.values()) {
      currentAvailableExecutorTime +=
          (time - executor.time()) * executor.executorInfo().totalCores();
    }

    return currentAvailableExecutorTime;
  }

  private void captureApplicationParameters(AgentTracer.SpanBuilder builder) {
    for (Map.Entry<String, String> entry : SparkConfAllowList.getRedactedSparkConf(sparkConf)) {
      builder.withTag("config." + entry.getKey().replace(".", "_"), entry.getValue());
    }
    builder.withTag("config.spark_version", sparkVersion);
  }

  private void captureJobParameters(AgentTracer.SpanBuilder builder, Properties properties) {
    for (Tuple2<String, String> conf : sparkConf.getAll()) {
      if (SparkConfAllowList.canCaptureJobParameter(conf._1)) {
        builder.withTag("config." + conf._1.replace(".", "_"), conf._2);
      }
    }

    // Parameters from properties overwrite values from the spark conf
    if (properties != null) {
      for (final Map.Entry<Object, Object> entry : properties.entrySet()) {
        if (SparkConfAllowList.canCaptureJobParameter(entry.getKey().toString())) {
          builder.withTag(
              "config." + entry.getKey().toString().replace('.', '_'), entry.getValue());
        }
      }
    }
    builder.withTag("config.spark_version", sparkVersion);
  }

  private static Long getSqlExecutionId(Properties properties) {
    if (properties == null) {
      return null;
    }

    String sqlExecutionId = properties.getProperty(SQLExecution.EXECUTION_ID_KEY());
    if (sqlExecutionId == null) {
      return null;
    }

    try {
      return Long.parseLong(sqlExecutionId);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static Long convertStringDateToMillis(String isoUtcDateStr) {
    if (isoUtcDateStr == null) {
      return null;
    }

    try {
      return OffsetDateTime.parse(isoUtcDateStr).toInstant().toEpochMilli();
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private static String getStreamingBatchKey(Properties properties) {
    if (properties == null) {
      return null;
    }

    Object queryId = properties.get(StreamExecution.QUERY_ID_KEY());
    Object batchId = properties.get(MicroBatchExecution.BATCH_ID_KEY());

    if (queryId == null || batchId == null) {
      return null;
    }

    return getStreamingBatchKey(queryId.toString(), batchId.toString());
  }

  private static String getStreamingBatchKey(String queryId, String batchId) {
    return queryId + "." + batchId;
  }

  private static String getBatchIdFromBatchKey(String batchKey) {
    return batchKey.substring(batchKey.lastIndexOf(".") + 1);
  }

  private static String getDatabricksServiceName(SparkConf conf, String databricksClusterName) {
    if (Config.get().isServiceNameSetByUser()) {
      return null;
    }

    String serviceName = null;
    String runName = getDatabricksRunName(conf);
    if (runName != null) {
      serviceName = "databricks.job-cluster." + runName;
    } else if (databricksClusterName != null) {
      serviceName = "databricks.all-purpose-cluster." + databricksClusterName;
    }

    return serviceName;
  }

  private static String getSparkServiceName(SparkConf conf, boolean isRunningOnDatabricks) {
    // If config is not set or running on databricks, not changing the service name
    if (!Config.get().useSparkAppNameAsService() || isRunningOnDatabricks) {
      return null;
    }

    // Keep service set by user, except if it is only "spark" or "hadoop" that can be set by USM
    String serviceName = Config.get().getServiceName();
    if (Config.get().isServiceNameSetByUser()
        && !"spark".equals(serviceName)
        && !"hadoop".equals(serviceName)) {
      log.debug("Service '{}' explicitly set by user, not using the application name", serviceName);
      return null;
    }

    String sparkAppName = conf.get("spark.app.name", null);
    if (sparkAppName != null) {
      log.info("Using Spark application name '{}' as the Datadog service name", sparkAppName);
    }

    return sparkAppName;
  }

  private static String getServiceForOpenLineage(SparkConf conf, boolean isRunningOnDatabricks) {
    // Service for OpenLineage in Databricks is not supported yet
    if (isRunningOnDatabricks) {
      return null;
    }

    // Keep service set by user, except if it is only "spark" or "hadoop" that can be set by USM
    String serviceName = Config.get().getServiceName();
    if (Config.get().isServiceNameSetByUser()
        && !"spark".equals(serviceName)
        && !"hadoop".equals(serviceName)) {
      log.debug(
          "Service explicitly set by user, not using the application name. Service name: {}",
          serviceName);
      return serviceName;
    }

    String sparkAppName = conf.get("spark.app.name", null);
    if (sparkAppName != null) {
      log.debug(
          "Using Spark application name as the Datadog service for OpenLineage. Spark application name: {}",
          sparkAppName);
    }

    return sparkAppName;
  }

  private void setupOpenLineageCircuitBreaker() {
    if (!Config.get().isDataJobsOpenLineageTimeoutEnabled()) {
      log.debug("Data Jobs OpenLineage timeout is not enabled");
      return;
    }
    if (!classIsLoadable("io.openlineage.client.circuitBreaker.TimeoutCircuitBreaker")) {
      log.debug(
          "OpenLineage version without timeout circuit breaker. Probably OL version < 1.35.0");
      return;
    }
    if (openLineageSparkConf.contains("spark.openlineage.circuitBreaker.type")) {
      log.debug(
          "Other OpenLineage circuit breaker already configured: {}",
          openLineageSparkConf.get("spark.openlineage.circuitBreaker.type"));
      return;
    }

    openLineageSparkConf.set("spark.openlineage.circuitBreaker.type", "timeout");
    openLineageSparkConf.setIfMissing(
        "spark.openlineage.circuitBreaker.timeoutInSeconds",
        String.valueOf(OL_CIRCUIT_BREAKER_TIMEOUT_IN_SECONDS));
    log.debug(
        "Setting OpenLineage circuit breaker with timeout {} seconds",
        openLineageSparkConf.get("spark.openlineage.circuitBreaker.timeoutInSeconds"));
  }

  private static void reportKafkaOffsets(
      final String appName, final AgentSpan span, final SourceProgress progress) {
    if (!traceConfig().isDataStreamsEnabled()
        || progress == null
        || progress.description() == null) {
      return;
    }

    // check if this is a kafka source
    if (progress.description().toLowerCase().startsWith("kafka")) {
      try {
        // parse offsets from endOffsets json, reported in a format:
        // "topic" -> ["partition":value]
        JsonNode jsonNode = objectMapper.readTree(progress.endOffset());
        Iterator<String> topics = jsonNode.fieldNames();
        // report offsets for all topics / partitions
        while (topics.hasNext()) {
          String topic = topics.next();
          JsonNode topicNode = jsonNode.get(topic);
          // iterate thought reported partitions
          Iterator<String> allPartitions = topicNode.fieldNames();
          while (allPartitions.hasNext()) {
            String partition = allPartitions.next();
            DataStreamsTags tags =
                DataStreamsTags.createWithPartition(
                    "kafka_commit", topic, partition, null, appName);
            AgentTracer.get()
                .getDataStreamsMonitoring()
                .trackBacklog(tags, topicNode.get(partition).asLong());
          }
        }
      } catch (Throwable e) {
        log.debug("Failed to parse kafka offsets", e);
      }
    }
  }

  private static String getDatabricksRunName(SparkConf conf) {
    String allTags = conf.get("spark.databricks.clusterUsageTags.clusterAllTags", null);
    if (allTags == null) {
      return null;
    }

    try {
      // Using the jackson JSON lib used by spark
      // https://mvnrepository.com/artifact/org.apache.spark/spark-core_2.12/3.5.0
      JsonNode jsonNode = objectMapper.readTree(allTags);

      for (JsonNode node : jsonNode) {
        String key = node.get("key").asText();
        if ("RunName".equals(key)) {
          // Databricks jobs launched by Azure Data Factory have an uuid at the end of the name
          return removeUuidFromEndOfString(node.get("value").asText());
        }
      }
    } catch (Exception ignored) {
    }

    return null;
  }

  private static String getAgentHttpUrl() {
    StringBuilder sb =
        new StringBuilder("http://")
            .append(Config.get().getAgentHost())
            .append(":")
            .append(Config.get().getAgentPort());
    return sb.toString();
  }

  @SuppressForbidden // called at most once per spark application
  private static String removeUuidFromEndOfString(String input) {
    return input.replaceAll(
        "_[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$", "");
  }
}
