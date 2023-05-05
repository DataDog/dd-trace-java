package datadog.trace.instrumentation.spark;

import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.apache.spark.SparkConf;
import org.apache.spark.TaskFailedReason;
import org.apache.spark.scheduler.*;
import scala.Tuple2;

/**
 * Implementation of the SparkListener {@link org.apache.spark.scheduler.SparkListener} to generate
 * spans from the execution of a spark application. All the callbacks are called inside the spark
 * driver and in the same thread
 */
public class DatadogSparkListener extends SparkListener {
  private final int MAX_COLLECTION_SIZE = 1000;

  private final SparkConf sparkConf;

  private final AgentTracer.TracerAPI tracer;

  private AgentSpan applicationSpan;
  private final HashMap<Integer, AgentSpan> jobSpans = new HashMap<>();
  private final HashMap<Long, AgentSpan> stageSpans = new HashMap<>();

  private final HashMap<Integer, Integer> stageToJob = new HashMap<>();

  private final SparkAggregatedTaskMetrics applicationMetrics = new SparkAggregatedTaskMetrics();
  private final HashMap<Integer, SparkAggregatedTaskMetrics> jobMetrics = new HashMap<>();
  private final HashMap<Long, SparkAggregatedTaskMetrics> stageMetrics = new HashMap<>();

  private final HashMap<String, SparkListenerExecutorAdded> liveExecutors = new HashMap<>();

  private final boolean isRunningOnDatabricks;

  private boolean lastJobFailed = false;
  private String lastJobFailedMessage;
  private int currentExecutorCount = 0;
  private int maxExecutorCount = 0;
  private long availableExecutorTime = 0;

  public DatadogSparkListener(SparkConf _sparkConf) {
    tracer = AgentTracer.get();
    sparkConf = _sparkConf;

    isRunningOnDatabricks = sparkConf.contains("spark.databricks.sparkContextId");
  }

  @Override
  public void onApplicationStart(SparkListenerApplicationStart applicationStart) {
    applicationSpan =
        tracer
            .buildSpan("spark.application")
            .withStartTimestamp(applicationStart.time() * 1000)
            .withTag("application_name", applicationStart.appName())
            .withTag("spark_user", applicationStart.sparkUser())
            .withSpanType("spark")
            .start();

    applicationSpan.setMeasured(true);

    if (applicationStart.appId().isDefined())
      applicationSpan.setTag("app_id", applicationStart.appId().get());
    if (applicationStart.appAttemptId().isDefined())
      applicationSpan.setTag("app_attempt_id", applicationStart.appAttemptId().get());

    for (Tuple2<String, String> conf : sparkConf.getAll()) {
      if (SparkConfAllowList.canCaptureApplicationParameter(conf._1)) {
        applicationSpan.setTag("config." + conf._1.replace(".", "_"), conf._2);
      }
    }
  }

  @Override
  public void onApplicationEnd(SparkListenerApplicationEnd applicationEnd) {
    if (lastJobFailed) {
      applicationSpan.setError(true);
      applicationSpan.setTag(DDTags.ERROR_TYPE, "Spark Application Failed");
      applicationSpan.setTag(DDTags.ERROR_MSG, lastJobFailedMessage);
    }

    for (SparkListenerExecutorAdded executor : liveExecutors.values()) {
      availableExecutorTime +=
          (applicationEnd.time() - executor.time()) * executor.executorInfo().totalCores();
    }

    applicationMetrics.setSpanMetrics(applicationSpan, "spark_application_metrics");
    applicationSpan.setMetric("spark_application_metrics.max_executor_count", maxExecutorCount);
    applicationSpan.setMetric(
        "spark_application_metrics.available_executor_time", availableExecutorTime);

    applicationSpan.finish(applicationEnd.time() * 1000);
  }

  @Override
  public void onJobStart(SparkListenerJobStart jobStart) {
    if (jobSpans.size() > MAX_COLLECTION_SIZE) {
      return;
    }

    AgentTracer.SpanBuilder jobSpanBuilder =
        tracer
            .buildSpan("spark.job")
            .withStartTimestamp(jobStart.time() * 1000)
            .withTag("job_id", jobStart.jobId())
            .withTag("stage_count", jobStart.stageInfos().size())
            .withTag(DDTags.RESOURCE_NAME, jobStart.stageInfos().apply(0).name())
            .withSpanType("spark");

    if (isRunningOnDatabricks) {
      // In databricks, the spark jobs are the local root spans so adding the spark conf parameters
      // to the job spans
      for (Tuple2<String, String> conf : sparkConf.getAll()) {
        if (SparkConfAllowList.canCaptureApplicationParameter(conf._1)) {
          jobSpanBuilder.withTag("config." + conf._1.replace(".", "_"), conf._2);
        }
      }

      if (jobStart.properties() != null) {
        // ids to link those spans to databricks job/task traces
        jobSpanBuilder.withTag(
            "databricks_job_id", jobStart.properties().get("spark.databricks.job.id"));
        jobSpanBuilder.withTag(
            "databricks_job_run_id", getDatabricksJobRunId(jobStart.properties()));

        // spark.databricks.job.runId is the runId of the task, not of the Job
        jobSpanBuilder.withTag(
            "databricks_task_run_id", jobStart.properties().get("spark.databricks.job.runId"));
      }
    } else {
      // In non-databricks env, the spark application is the local root spans
      jobSpanBuilder.asChildOf(applicationSpan.context());
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

    jobStart.stageInfos().foreach(stage -> stageToJob.put(stage.stageId(), jobStart.jobId()));

    jobSpans.put(jobStart.jobId(), jobSpan);
  }

  @Override
  public void onJobEnd(SparkListenerJobEnd jobEnd) {
    AgentSpan jobSpan = jobSpans.remove(jobEnd.jobId());
    if (jobSpan == null) {
      return;
    }

    lastJobFailed = false;
    if (jobEnd.jobResult() instanceof JobFailed) {
      JobFailed jobFailed = (JobFailed) jobEnd.jobResult();

      jobSpan.setError(true);
      jobSpan.setErrorMessage(jobFailed.exception().toString());
      jobSpan.setTag(DDTags.ERROR_TYPE, "Spark Job Failed");
      lastJobFailed = true;
      lastJobFailedMessage = jobFailed.exception().toString();
    }

    SparkAggregatedTaskMetrics metrics = jobMetrics.remove(jobEnd.jobId());
    if (metrics != null) {
      metrics.setSpanMetrics(jobSpan, "spark_job_metrics");
    }

    jobSpan.finish(jobEnd.time() * 1000);
  }

  @Override
  public void onStageSubmitted(SparkListenerStageSubmitted stageSubmitted) {
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

    AgentSpan stageSpan =
        tracer
            .buildSpan("spark.stage")
            .asChildOf(jobSpan.context())
            .withStartTimestamp(submissionTimeMs * 1000)
            .withTag("stage_id", stageId)
            .withTag("task_count", stageSubmitted.stageInfo().numTasks())
            .withTag("attempt_id", stageAttemptId)
            .withTag("parent_stages_ids", stageSubmitted.stageInfo().parentIds())
            .withTag("details", stageSubmitted.stageInfo().details())
            .withTag(DDTags.RESOURCE_NAME, stageSubmitted.stageInfo().name())
            .withSpanType("spark")
            .start();

    stageSpan.setMeasured(true);

    stageSpans.put(stageSpanKey(stageId, stageAttemptId), stageSpan);
  }

  @Override
  public void onStageCompleted(SparkListenerStageCompleted stageCompleted) {
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
      span.setErrorMessage(stageInfo.failureReason().get());
      span.setTag(DDTags.ERROR_TYPE, "Spark Stage Failed");
    }

    stageInfo.rddInfos().foreach(rdd -> span.setTag("rdd." + rdd.name(), rdd.toString()));

    SparkAggregatedTaskMetrics stageMetric = stageMetrics.remove(stageSpanKey);
    if (stageMetric != null) {
      stageMetric.setSpanMetrics(span, "spark_stage_metrics");
      applicationMetrics.accumulateStageMetrics(stageMetric);

      jobMetrics
          .computeIfAbsent(jobId, k -> new SparkAggregatedTaskMetrics())
          .accumulateStageMetrics(stageMetric);
    }

    long completionTimeMs;
    if (stageCompleted.stageInfo().completionTime().isDefined()) {
      completionTimeMs = (long) stageCompleted.stageInfo().completionTime().get();
    } else {
      completionTimeMs = System.currentTimeMillis();
    }

    span.finish(completionTimeMs * 1000);
  }

  @Override
  public void onTaskEnd(SparkListenerTaskEnd taskEnd) {
    if (jobMetrics.size() > MAX_COLLECTION_SIZE || stageMetrics.size() > MAX_COLLECTION_SIZE) {
      return;
    }

    int stageId = taskEnd.stageId();
    int stageAttemptId = taskEnd.stageAttemptId();
    long stageSpanKey = stageSpanKey(stageId, stageAttemptId);

    stageMetrics
        .computeIfAbsent(stageSpanKey, k -> new SparkAggregatedTaskMetrics())
        .addTaskMetrics(taskEnd);

    // Only sending failing tasks
    if (!(taskEnd.reason() instanceof TaskFailedReason)) {
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
        tracer
            .buildSpan("spark.task")
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
            .withSpanType("spark")
            .start();

    if (taskEnd.reason() instanceof TaskFailedReason) {
      TaskFailedReason reason = (TaskFailedReason) taskEnd.reason();

      taskSpan.setError(true);
      taskSpan.setErrorMessage(reason.toErrorString());
      taskSpan.setTag(DDTags.ERROR_TYPE, "Spark Task Failed");
      taskSpan.setTag("count_towards_task_failures", reason.countTowardsTaskFailures());
    }

    taskSpan.finish(taskEnd.taskInfo().finishTime() * 1000);
  }

  @Override
  public void onExecutorAdded(SparkListenerExecutorAdded executorAdded) {
    currentExecutorCount += 1;
    maxExecutorCount = Math.max(maxExecutorCount, currentExecutorCount);

    if (liveExecutors.size() <= MAX_COLLECTION_SIZE) {
      liveExecutors.put(executorAdded.executorId(), executorAdded);
    }
  }

  @Override
  public void onExecutorRemoved(SparkListenerExecutorRemoved executorRemoved) {
    currentExecutorCount -= 1;

    SparkListenerExecutorAdded executor = liveExecutors.remove(executorRemoved.executorId());
    if (executor != null) {
      availableExecutorTime +=
          (executorRemoved.time() - executor.time()) * executor.executorInfo().totalCores();
    }
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
}
