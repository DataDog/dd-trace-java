package datadog.trace.instrumentation.spark;

import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.HashMap;
import java.util.Optional;
import org.apache.spark.SparkConf;
import org.apache.spark.TaskFailedReason;
import org.apache.spark.scheduler.*;
import scala.Tuple2;

public class InjectedSparkListener extends SparkListener {
  String serviceName = "apache-spark";

  SparkConf sparkConf;

  AgentTracer.TracerAPI tracer;

  AgentSpan applicationSpan;
  HashMap<Integer, AgentSpan> jobSpans = new HashMap<>();
  HashMap<Integer, AgentSpan> stageSpans = new HashMap<>();

  HashMap<Integer, Integer> stageToJob = new HashMap<>();

  SparkAggregatedTaskMetrics applicationMetrics = new SparkAggregatedTaskMetrics();
  HashMap<Integer, SparkAggregatedTaskMetrics> jobMetrics = new HashMap<>();
  HashMap<Integer, SparkAggregatedTaskMetrics> stageMetrics = new HashMap<>();

  HashMap<Integer, SparkListenerTaskEnd> stageLongestTask = new HashMap<>();
  HashMap<String, SparkListenerExecutorAdded> liveExecutors = new HashMap<>();

  boolean lastJobFailed = false;
  int currentExecutorCount = 0;
  int maxExecutorCount = 0;
  long availableExecutorTime = 0;

  public InjectedSparkListener(SparkConf _sparkConf) {
    tracer = AgentTracer.get();
    sparkConf = _sparkConf;
  }

  @Override
  public void onApplicationStart(SparkListenerApplicationStart applicationStart) {
    applicationSpan =
        tracer
            .buildSpan("spark.application")
            .withStartTimestamp(applicationStart.time() * 1000)
            .withTag("application_name", applicationStart.appName())
            .withTag("spark_user", applicationStart.sparkUser())
            .withTag(DDTags.SERVICE_NAME, serviceName)
            .withTag(DDTags.MEASURED, 1)
            .withSpanType("spark")
            .start();

    if (applicationStart.appId().isDefined())
      applicationSpan.setTag("app_id", applicationStart.appId().get());
    if (applicationStart.appAttemptId().isDefined())
      applicationSpan.setTag("app_attempt_id", applicationStart.appAttemptId().get());

    for (Tuple2<String, String> conf : sparkConf.getAll()) {
      applicationSpan.setTag("config." + conf._1.replace(".", "_"), conf._2);
    }
  }

  @Override
  public void onApplicationEnd(SparkListenerApplicationEnd applicationEnd) {
    if (lastJobFailed) {
      applicationSpan.setError(true);
      applicationSpan.setTag(DDTags.ERROR_TYPE, "Spark Application Failed");
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
    tracer.flush();
  }

  @Override
  public void onJobStart(SparkListenerJobStart jobStart) {
    AgentSpan jobSpan =
        tracer
            .buildSpan("spark.job")
            .asChildOf(applicationSpan.context())
            .withStartTimestamp(jobStart.time() * 1000)
            .withTag("job_id", jobStart.jobId())
            .withTag("stage_count", jobStart.stageInfos().size())
            .withTag(DDTags.RESOURCE_NAME, jobStart.stageInfos().apply(0).name())
            .withTag(DDTags.SERVICE_NAME, serviceName)
            .withTag(DDTags.MEASURED, 1)
            .withSpanType("spark")
            .start();

    if (jobStart.properties() != null) {
      jobStart
          .properties()
          .forEach((k, v) -> jobSpan.setTag("config." + k.toString().replace('.', '_'), v));
    }

    jobStart.stageInfos().foreach(stage -> stageToJob.put(stage.stageId(), jobStart.jobId()));

    jobSpans.put(jobStart.jobId(), jobSpan);
  }

  @Override
  public void onJobEnd(SparkListenerJobEnd jobEnd) {
    if (!jobSpans.containsKey(jobEnd.jobId())) {
      return;
    }

    AgentSpan jobSpan = jobSpans.get(jobEnd.jobId());

    lastJobFailed = false;
    if (jobEnd.jobResult() instanceof JobFailed) {
      jobSpan.setError(true);
      jobSpan.setErrorMessage(jobEnd.jobResult().toString());
      jobSpan.setTag(DDTags.ERROR_TYPE, "Spark Job Failed");
      lastJobFailed = true;
    }

    if (jobMetrics.containsKey(jobEnd.jobId())) {
      SparkAggregatedTaskMetrics metrics = jobMetrics.get(jobEnd.jobId());
      metrics.setSpanMetrics(jobSpan, "spark_job_metrics");
    }

    jobSpan.finish(jobEnd.time() * 1000);
  }

  @Override
  public void onStageSubmitted(SparkListenerStageSubmitted stageSubmitted) {
    int stageId = stageSubmitted.stageInfo().stageId();
    int stageAttemptId = stageSubmitted.stageInfo().attemptNumber();

    if (!stageToJob.containsKey(stageId)) {
      return;
    }

    int jobId = stageToJob.get(stageId);

    if (!jobSpans.containsKey(jobId)) {
      return;
    }

    AgentSpan jobSpan = jobSpans.get(jobId);

    Optional<Long> submissionTime =
        Optional.ofNullable(stageSubmitted.stageInfo().submissionTime().getOrElse(null));

    AgentSpan stageSpan =
        tracer
            .buildSpan("spark.stage")
            .asChildOf(jobSpan.context())
            .withStartTimestamp(submissionTime.orElse(System.currentTimeMillis()) * 1000)
            .withTag("stage_id", stageId)
            .withTag("task_count", stageSubmitted.stageInfo().numTasks())
            .withTag("attempt_id", stageAttemptId)
            .withTag("parent_stages_ids", stageSubmitted.stageInfo().parentIds())
            .withTag("details", stageSubmitted.stageInfo().details())
            .withTag(DDTags.RESOURCE_NAME, stageSubmitted.stageInfo().name())
            .withTag(DDTags.SERVICE_NAME, serviceName)
            .withTag(DDTags.MEASURED, 1)
            .withSpanType("spark")
            .start();

    stageSpans.put(stageSpanKey(stageId, stageAttemptId), stageSpan);
  }

  @Override
  public void onStageCompleted(SparkListenerStageCompleted stageCompleted) {
    StageInfo stageInfo = stageCompleted.stageInfo();
    int stageId = stageInfo.stageId();
    int stageAttemptId = stageInfo.attemptNumber();

    if (!stageToJob.containsKey(stageId)) {
      return;
    }

    int jobId = stageToJob.get(stageId);

    int stageSpanKey = stageSpanKey(stageId, stageAttemptId);
    if (!stageSpans.containsKey(stageSpanKey)) {
      return;
    }

    AgentSpan span = stageSpans.get(stageSpanKey);

    if (stageInfo.failureReason().isDefined()) {
      span.setError(true);
      span.setErrorMessage(stageInfo.failureReason().get());
      span.setTag(DDTags.ERROR_TYPE, "Spark Stage Failed");
    }

    stageInfo.rddInfos().foreach(rdd -> span.setTag("rdd." + rdd.name(), rdd.toString()));

    if (stageMetrics.containsKey(stageSpanKey)) {
      SparkAggregatedTaskMetrics stageMetric = stageMetrics.get(stageSpanKey);
      stageMetric.setSpanMetrics(span, "spark_stage_metrics");

      applicationMetrics.accumulateStageMetrics(stageMetric);

      if (!jobMetrics.containsKey(jobId)) {
        jobMetrics.put(jobId, new SparkAggregatedTaskMetrics());
      }
      jobMetrics.get(jobId).accumulateStageMetrics(stageMetric);
    }

    if (stageLongestTask.containsKey(stageSpanKey)) {
      SparkListenerTaskEnd longestTask = stageLongestTask.get(stageSpanKey);
      sendTaskSpan(span, longestTask);
    }

    Optional<Long> completionTime =
        Optional.ofNullable(stageCompleted.stageInfo().completionTime().getOrElse(null));
    span.finish(completionTime.orElse(System.currentTimeMillis()) * 1000);
  }

  @Override
  public void onTaskEnd(SparkListenerTaskEnd taskEnd) {
    int stageId = taskEnd.stageId();
    int stageAttemptId = taskEnd.stageAttemptId();
    int stageSpanKey = stageSpanKey(stageId, stageAttemptId);

    if (!stageMetrics.containsKey(stageSpanKey)) {
      stageMetrics.put(stageSpanKey, new SparkAggregatedTaskMetrics());
    }
    stageMetrics.get(stageSpanKey).addTaskMetrics(taskEnd);

    // Keep track of the longest task per stage, to only send this one to the backend
    if (stageLongestTask.containsKey(stageSpanKey)) {
      TaskInfo taskInfo = taskEnd.taskInfo();

      if (taskInfo.duration() > stageLongestTask.get(stageSpanKey).taskInfo().duration()) {
        stageLongestTask.put(stageSpanKey, taskEnd);
      }
    } else {
      stageLongestTask.put(stageSpanKey, taskEnd);
    }

    // Also sending all the failing tasks
    if (!(taskEnd.reason() instanceof TaskFailedReason)) {
      return;
    }
    if (!stageSpans.containsKey(stageSpanKey)) {
      return;
    }

    AgentSpan stageSpan = stageSpans.get(stageSpanKey);
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
            .withTag(DDTags.SERVICE_NAME, serviceName)
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

    liveExecutors.put(executorAdded.executorId(), executorAdded);
  }

  @Override
  public void onExecutorRemoved(SparkListenerExecutorRemoved executorRemoved) {
    currentExecutorCount -= 1;

    if (liveExecutors.containsKey(executorRemoved.executorId())) {
      SparkListenerExecutorAdded executor = liveExecutors.get(executorRemoved.executorId());

      availableExecutorTime +=
          (executorRemoved.time() - executor.time()) * executor.executorInfo().totalCores();
      liveExecutors.remove(executor.executorId());
    }
  }

  private int stageSpanKey(int stageId, int attemptId) {
    return stageId * 100000 + attemptId;
  }
}
