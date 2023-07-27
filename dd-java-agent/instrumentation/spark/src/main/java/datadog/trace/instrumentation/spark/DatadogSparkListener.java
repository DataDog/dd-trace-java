package datadog.trace.instrumentation.spark;

import datadog.trace.api.DDTags;
import datadog.trace.api.DDTraceId;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.spark.ExceptionFailure;
import org.apache.spark.SparkConf;
import org.apache.spark.TaskFailedReason;
import org.apache.spark.scheduler.*;
import org.apache.spark.sql.execution.streaming.MicroBatchExecution;
import org.apache.spark.sql.execution.streaming.StreamExecution;
import org.apache.spark.sql.streaming.SourceProgress;
import org.apache.spark.sql.streaming.StateOperatorProgress;
import org.apache.spark.sql.streaming.StreamingQueryListener;
import org.apache.spark.sql.streaming.StreamingQueryProgress;
import scala.Tuple2;

/**
 * Implementation of the SparkListener {@link org.apache.spark.scheduler.SparkListener} to generate
 * spans from the execution of a spark application.
 *
 * <p>All the callbacks are called inside the spark driver and in the same thread, but since some
 * methods (like finishApplication) are called from the instrumentation advice, thread-safety is
 * still needed
 */
public class DatadogSparkListener extends SparkListener {
  public static volatile DatadogSparkListener listener = null;
  public static volatile boolean finishTraceOnApplicationEnd = true;

  private final int MAX_COLLECTION_SIZE = 1000;

  private final SparkConf sparkConf;
  private final String sparkVersion;
  private final String appId;

  private final AgentTracer.TracerAPI tracer;

  private AgentSpan applicationSpan;
  private SparkListenerApplicationStart applicationStart;
  private final HashMap<String, AgentSpan> streamingBatchSpans = new HashMap<>();
  private final HashMap<Integer, AgentSpan> jobSpans = new HashMap<>();
  private final HashMap<Long, AgentSpan> stageSpans = new HashMap<>();

  private final HashMap<Integer, Integer> stageToJob = new HashMap<>();
  private final HashMap<Long, Properties> stageProperties = new HashMap<>();

  private final SparkAggregatedTaskMetrics applicationMetrics = new SparkAggregatedTaskMetrics();
  private final HashMap<String, SparkAggregatedTaskMetrics> streamingBatchMetrics = new HashMap<>();
  private final HashMap<Integer, SparkAggregatedTaskMetrics> jobMetrics = new HashMap<>();
  private final HashMap<Long, SparkAggregatedTaskMetrics> stageMetrics = new HashMap<>();

  private final HashMap<UUID, StreamingQueryListener.QueryStartedEvent> streamingQueries =
      new HashMap<>();
  private final HashMap<String, SparkListenerExecutorAdded> liveExecutors = new HashMap<>();

  private final boolean isRunningOnDatabricks;

  private boolean lastJobFailed = false;
  private String lastJobFailedMessage;
  private String lastJobFailedStackTrace;
  private int jobCount = 0;
  private int currentExecutorCount = 0;
  private int maxExecutorCount = 0;
  private long availableExecutorTime = 0;

  private boolean applicationEnded = false;

  public DatadogSparkListener(SparkConf sparkConf, String appId, String sparkVersion) {
    tracer = AgentTracer.get();

    this.sparkConf = sparkConf;
    this.appId = appId;
    this.sparkVersion = sparkVersion;

    isRunningOnDatabricks = sparkConf.contains("spark.databricks.sparkContextId");
  }

  @Override
  public synchronized void onApplicationStart(SparkListenerApplicationStart applicationStart) {
    this.applicationStart = applicationStart;
  }

  private void initApplicationSpanIfNotInitialized() {
    if (applicationSpan != null) {
      return;
    }

    AgentTracer.SpanBuilder builder = buildSparkSpan("spark.application");

    if (applicationStart != null) {
      builder
          .withStartTimestamp(applicationStart.time() * 1000)
          .withTag("application_name", applicationStart.appName())
          .withTag("spark_user", applicationStart.sparkUser());

      if (applicationStart.appAttemptId().isDefined()) {
        builder.withTag("app_attempt_id", applicationStart.appAttemptId().get());
      }
    }

    for (Tuple2<String, String> conf : sparkConf.getAll()) {
      if (SparkConfAllowList.canCaptureApplicationParameter(conf._1)) {
        builder.withTag("config." + conf._1.replace(".", "_"), conf._2);
      }
    }
    builder.withTag("config.spark_version", sparkVersion);

    applicationSpan = builder.start();
    applicationSpan.setMeasured(true);
  }

  @Override
  public void onApplicationEnd(SparkListenerApplicationEnd applicationEnd) {
    if (finishTraceOnApplicationEnd) {
      finishApplication(applicationEnd.time(), null, 0, null);
    }
  }

  public synchronized void finishApplication(
      long time, Throwable throwable, int exitCode, String msg) {
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

    applicationMetrics.setSpanMetrics(applicationSpan, "spark_application_metrics");
    applicationSpan.setMetric("spark_application_metrics.max_executor_count", maxExecutorCount);
    applicationSpan.setMetric(
        "spark_application_metrics.available_executor_time",
        computeCurrentAvailableExecutorTime(time));

    applicationSpan.finish(time * 1000);
  }

  @Override
  public synchronized void onJobStart(SparkListenerJobStart jobStart) {
    jobCount++;
    if (jobSpans.size() > MAX_COLLECTION_SIZE) {
      return;
    }

    AgentTracer.SpanBuilder jobSpanBuilder =
        buildSparkSpan("spark.job")
            .withStartTimestamp(jobStart.time() * 1000)
            .withTag("job_id", jobStart.jobId())
            .withTag("stage_count", jobStart.stageInfos().size());

    String batchKey = getStreamingBatchKey(jobStart.properties());

    if (batchKey != null) {
      AgentSpan batchSpan = streamingBatchSpans.get(batchKey);

      if (batchSpan == null) {
        batchSpan =
            buildSparkSpan("spark.batch").withStartTimestamp(jobStart.time() * 1000).start();

        streamingBatchSpans.put(batchKey, batchSpan);
      }

      // In streaming mode, the batch spans are the local root spans
      jobSpanBuilder.asChildOf(batchSpan.context());
    } else if (isRunningOnDatabricks) {
      // In databricks, the spark jobs are the local root spans so adding the spark conf parameters
      // to the job spans
      for (Tuple2<String, String> conf : sparkConf.getAll()) {
        if (SparkConfAllowList.canCaptureApplicationParameter(conf._1)) {
          jobSpanBuilder.withTag("config." + conf._1.replace(".", "_"), conf._2);
        }
      }

      if (jobStart.properties() != null) {
        String databricksJobId = (String) jobStart.properties().get("spark.databricks.job.id");
        String databricksJobRunId = getDatabricksJobRunId(jobStart.properties());

        // spark.databricks.job.runId is the runId of the task, not of the Job
        String databricksTaskRunId =
            (String) jobStart.properties().get("spark.databricks.job.runId");

        // ids to link those spans to databricks job/task traces
        jobSpanBuilder.withTag("databricks_job_id", databricksJobId);
        jobSpanBuilder.withTag("databricks_job_run_id", databricksJobRunId);
        jobSpanBuilder.withTag("databricks_task_run_id", databricksTaskRunId);

        AgentSpan.Context parentContext =
            new DatabricksParentContext(databricksJobId, databricksJobRunId, databricksTaskRunId);

        if (parentContext.getTraceId() != DDTraceId.ZERO) {
          jobSpanBuilder.asChildOf(parentContext);
        }
      }
    } else {
      // In non-databricks, non-streaming env, the spark application is the local root span
      initApplicationSpanIfNotInitialized();
      jobSpanBuilder.asChildOf(applicationSpan.context());
    }

    if (jobStart.stageInfos().nonEmpty()) {
      // In the spark UI, the name of a job is the name of its last stage
      jobSpanBuilder.withTag(DDTags.RESOURCE_NAME, jobStart.stageInfos().last().name());
    }

    AgentSpan jobSpan = jobSpanBuilder.start();
    jobSpan.setMeasured(true);

    // Some properties can change at runtime, so capturing properties of all jobs
    if (jobStart.properties() != null) {
      for (final Map.Entry<Object, Object> entry : jobStart.properties().entrySet()) {
        if (SparkConfAllowList.canCaptureJobParameter(entry.getKey().toString())) {
          jobSpan.setTag("config." + entry.getKey().toString().replace('.', '_'), entry.getValue());
        }
      }
    }
    jobSpan.setTag("config.spark_version", sparkVersion);

    jobStart.stageInfos().foreach(stage -> stageToJob.put(stage.stageId(), jobStart.jobId()));

    jobSpans.put(jobStart.jobId(), jobSpan);
  }

  @Override
  public synchronized void onJobEnd(SparkListenerJobEnd jobEnd) {
    AgentSpan jobSpan = jobSpans.remove(jobEnd.jobId());
    if (jobSpan == null) {
      return;
    }

    lastJobFailed = false;
    if (jobEnd.jobResult() instanceof JobFailed) {
      JobFailed jobFailed = (JobFailed) jobEnd.jobResult();
      Exception exception = jobFailed.exception();

      String errorMessage = getErrorMessageWithoutStackTrace(exception.getMessage());
      String errorStackTrace = stackTraceToString(exception);

      jobSpan.setError(true);
      jobSpan.setErrorMessage(errorMessage);
      jobSpan.setTag(DDTags.ERROR_STACK, errorStackTrace);
      jobSpan.setTag(DDTags.ERROR_TYPE, "Spark Job Failed");
      lastJobFailed = true;
      lastJobFailedMessage = errorMessage;
      lastJobFailedStackTrace = errorStackTrace;
    }

    SparkAggregatedTaskMetrics metrics = jobMetrics.remove(jobEnd.jobId());
    if (metrics != null) {
      metrics.setSpanMetrics(jobSpan, "spark_job_metrics");
    }

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
        buildSparkSpan("spark.stage")
            .asChildOf(jobSpan.context())
            .withStartTimestamp(submissionTimeMs * 1000)
            .withTag("stage_id", stageId)
            .withTag("task_count", stageSubmitted.stageInfo().numTasks())
            .withTag("attempt_id", stageAttemptId)
            .withTag("parent_stages_ids", stageSubmitted.stageInfo().parentIds())
            .withTag("details", stageSubmitted.stageInfo().details())
            .withTag(DDTags.RESOURCE_NAME, stageSubmitted.stageInfo().name())
            .start();

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

    if (stageInfo.failureReason().isDefined()) {
      span.setError(true);
      span.setErrorMessage(getErrorMessageWithoutStackTrace(stageInfo.failureReason().get()));
      span.setTag(DDTags.ERROR_STACK, stageInfo.failureReason().get());
      span.setTag(DDTags.ERROR_TYPE, "Spark Stage Failed");
    }

    stageInfo.rddInfos().foreach(rdd -> span.setTag("rdd." + rdd.name(), rdd.toString()));

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

    SparkAggregatedTaskMetrics stageMetric = stageMetrics.remove(stageSpanKey);
    if (stageMetric != null) {
      stageMetric.computeSkew();
      stageMetric.setSpanMetrics(span, "spark_stage_metrics");
      applicationMetrics.accumulateStageMetrics(stageMetric);

      jobMetrics
          .computeIfAbsent(jobId, k -> new SparkAggregatedTaskMetrics())
          .accumulateStageMetrics(stageMetric);

      Properties prop = stageProperties.remove(stageSpanKey);
      String batchKey = getStreamingBatchKey(prop);

      if (batchKey != null) {
        streamingBatchMetrics
            .computeIfAbsent(batchKey, k -> new SparkAggregatedTaskMetrics())
            .accumulateStageMetrics(stageMetric);
      }
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

    sendTaskSpan(stageSpan, taskEnd);
  }

  private void sendTaskSpan(AgentSpan stageSpan, SparkListenerTaskEnd taskEnd) {
    AgentSpan taskSpan =
        buildSparkSpan("spark.task")
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
          metrics.setSpanMetrics(batchSpan, "spark");
        }

        batchSpan.setTag("id", event.id());
        batchSpan.setTag("run_id", event.runId());
        batchSpan.setTag("batch_id", getBatchIdFromBatchKey(batchKey));
        if (startedEvent != null) {
          batchSpan.setTag("name", startedEvent.name());
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
        metrics.setSpanMetrics(batchSpan, "spark");
      }

      batchSpan.setTag("id", progress.id());
      batchSpan.setTag("run_id", progress.runId());
      batchSpan.setTag("batch_id", progress.batchId());
      batchSpan.setTag("name", progress.name());
      batchSpan.setTag(DDTags.RESOURCE_NAME, progress.name());

      batchSpan.setMetric("spark.num_input_rows", progress.numInputRows());
      batchSpan.setMetric("spark.input_rows_per_second", progress.inputRowsPerSecond());
      batchSpan.setMetric("spark.processed_rows_per_second", progress.processedRowsPerSecond());

      Long watermark = convertStringDateToMillis(progress.eventTime().get("watermark"));
      if (watermark != null) {
        batchSpan.setMetric("spark.event_time.watermark", watermark);
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

  private AgentTracer.SpanBuilder buildSparkSpan(String spanName) {
    return tracer.buildSpan(spanName).withSpanType("spark").withTag("app_id", appId);
  }

  private long stageSpanKey(int stageId, int attemptId) {
    return ((long) stageId << 32) + attemptId;
  }

  @SuppressForbidden // split with one-char String use a fast-path without regex usage
  private static String getDatabricksJobRunId(Properties jobProperties) {
    String clusterName =
        (String) jobProperties.get("spark.databricks.clusterUsageTags.clusterName");
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
}
