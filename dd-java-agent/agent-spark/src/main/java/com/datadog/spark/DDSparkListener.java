package com.datadog.spark;

import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import org.apache.spark.SparkConf;
import org.apache.spark.TaskFailedReason;
import org.apache.spark.executor.TaskMetrics;
import org.apache.spark.scheduler.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Optional;

import scala.Tuple2;


public class DDSparkListener implements SparkListenerInterface {

  private static final Logger log = LoggerFactory.getLogger(DDSparkListener.class);

  SparkConf sparkConf;

  AgentTracer.TracerAPI tracer;

  AgentSpan applicationSpan;

  String serviceName = "instrumented_spark";

  HashMap<Integer, AgentSpan> jobSpans = new HashMap<>();
  HashMap<Integer, AgentSpan> stageSpans = new HashMap<>();

  HashMap<Integer, Integer> stageToJob = new HashMap<>();

  DDSparkTaskMetrics applicationMetrics = new DDSparkTaskMetrics();
  HashMap<Integer, DDSparkTaskMetrics> jobMetrics = new HashMap<>();

  HashMap<Integer, SparkListenerTaskEnd> stageLongestTask = new HashMap<>();
  HashMap<String, SparkListenerExecutorAdded> liveExecutors = new HashMap<>();

  boolean lastJobFailed = false;
  int currentExecutorCount = 0;
  int maxExecutorCount = 0;
  long availableExecutorTime = 0;

  public DDSparkListener(SparkConf _sparkConf) {
    log.info("==DDSparkListener created!==");
    tracer = AgentTracer.get();
    sparkConf = _sparkConf;
  }

  @Override
  public void onApplicationStart(SparkListenerApplicationStart applicationStart) {
    log.info("Received onApplicationStart");

    applicationSpan = tracer.buildSpan("application")
      .withStartTimestamp(applicationStart.time() * 1000)
      .withTag("poc_spark_span", "true")
      .withTag("application_name", applicationStart.appName())
      .withTag("spark_user", applicationStart.sparkUser())
      .withTag(DDTags.SERVICE_NAME, serviceName)
      .withTag(DDTags.MEASURED, 1)
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
    log.info("Received onApplicationEnd");

    if (lastJobFailed) {
      applicationSpan.setError(true);
      applicationSpan.setTag(DDTags.ERROR_TYPE, "Last Spark Job Failed");
    }

    for (SparkListenerExecutorAdded executor : liveExecutors.values()) {
      availableExecutorTime += (applicationEnd.time() - executor.time()) * executor.executorInfo().totalCores();
    }

    applicationMetrics.setSpanMetrics(applicationSpan, "application");
    applicationSpan.setTag("application.max_executor_count", maxExecutorCount);
    applicationSpan.setTag("application.available_executor_time", availableExecutorTime);

    applicationSpan.finish(applicationEnd.time() * 1000);
    tracer.flush();
  }

  @Override
  public void onJobStart(SparkListenerJobStart jobStart) {
    log.info("Received onJobStart");

    AgentSpan jobSpan = tracer
      .buildSpan("job")
      .asChildOf(applicationSpan.context())
      .withStartTimestamp(jobStart.time() * 1000)
      .withTag("poc_spark_span", "true")
      .withTag("job_id", jobStart.jobId())
      .withTag("stage_count", jobStart.stageInfos().size())
      .withTag(DDTags.RESOURCE_NAME, jobStart.stageInfos().apply(0).name())
      .withTag(DDTags.SERVICE_NAME, serviceName)
      .withTag(DDTags.MEASURED, 1)
      .start();

    if (jobStart.properties() != null) {
      jobStart.properties().forEach((k, v) -> jobSpan.setTag("config." + k.toString().replace('.', '_'), v));
    }

    jobStart.stageInfos().foreach(stage -> stageToJob.put(stage.stageId(), jobStart.jobId()));

    jobSpans.put(jobStart.jobId(), jobSpan);
  }

  @Override
  public void onJobEnd(SparkListenerJobEnd jobEnd) {
    log.info("Received onJobEnd");

    if (!jobSpans.containsKey(jobEnd.jobId())) {
      log.warn(String.format("No span found for jobId %s", jobEnd.jobId()));
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
      DDSparkTaskMetrics metrics = jobMetrics.get(jobEnd.jobId());
      metrics.setSpanMetrics(jobSpan, "job");
    }

    jobSpan.finish(jobEnd.time() * 1000);
  }


  @Override
  public void onStageSubmitted(SparkListenerStageSubmitted stageSubmitted) {
    log.info("Received onStageSubmitted");

    int stageId = stageSubmitted.stageInfo().stageId();
    int stageAttemptId = stageSubmitted.stageInfo().attemptNumber();

    if (!stageToJob.containsKey(stageId)) {
      log.warn(String.format("No jobId found for stageId %s", stageId));
      return;
    }

    int jobId = stageToJob.get(stageId);

    if (!jobSpans.containsKey(jobId)) {
      log.warn(String.format("No job span found for stageId %s", stageId));
      return;
    }

    AgentSpan jobSpan = jobSpans.get(jobId);

    Optional<Long> submissionTime = Optional.ofNullable(stageSubmitted.stageInfo().submissionTime().getOrElse(null));

    AgentSpan stageSpan = tracer
      .buildSpan("stage")
      .asChildOf(jobSpan.context())
      .withStartTimestamp(submissionTime.orElse(System.currentTimeMillis()) * 1000)
      .withTag("poc_spark_span", "true")
      .withTag("stage_id", stageId)
      .withTag("task_count", stageSubmitted.stageInfo().numTasks())
      .withTag("attempt_id", stageAttemptId)
      .withTag("resource_profile_id", stageSubmitted.stageInfo().resourceProfileId())
      .withTag("parent_stages_ids", stageSubmitted.stageInfo().parentIds())
      .withTag("details", stageSubmitted.stageInfo().details())
      .withTag(DDTags.RESOURCE_NAME, stageSubmitted.stageInfo().name())
      .withTag(DDTags.SERVICE_NAME, serviceName)
      .withTag(DDTags.MEASURED, 1)
      .start();

    stageSpans.put(stageSpanKey(stageId, stageAttemptId), stageSpan);
  }

  @Override
  public void onStageCompleted(SparkListenerStageCompleted stageCompleted) {
    log.info("Received onStageCompleted");

    StageInfo stageInfo = stageCompleted.stageInfo();
    int stageId = stageInfo.stageId();
    int stageAttemptId = stageInfo.attemptNumber();

    if (!stageToJob.containsKey(stageId)) {
      log.warn(String.format("No jobId found for stageId %s", stageId));
      return;
    }

    int jobId = stageToJob.get(stageId);

    int stageSpanKey = stageSpanKey(stageId, stageAttemptId);
    if (!stageSpans.containsKey(stageSpanKey)) {
      log.warn(String.format("No stage span found for stageId %s", stageId));
      return;
    }

    AgentSpan span = stageSpans.get(stageSpanKey);

    if (stageInfo.failureReason().isDefined()) {
      span.setError(true);
      span.setErrorMessage(stageInfo.failureReason().get());
      span.setTag(DDTags.ERROR_TYPE, "Spark Stage Failed");
    }

    stageInfo.rddInfos().foreach(rdd -> span.setTag("rdd." + rdd.name(), rdd.toString()));

    TaskMetrics metrics = stageInfo.taskMetrics();
    if (metrics != null) {
      DDSparkTaskMetrics ddTaskMetrics = new DDSparkTaskMetrics(metrics);
      ddTaskMetrics.setSpanMetrics(span, "stage");

      applicationMetrics.addTaskMetrics(metrics);
      if (jobMetrics.containsKey(jobId)) {
        jobMetrics.get(jobId).addTaskMetrics(metrics);
      }
      else {
        jobMetrics.put(jobId, new DDSparkTaskMetrics(metrics));
      }
    }

    if (stageLongestTask.containsKey(stageSpanKey)) {
      SparkListenerTaskEnd longestTask = stageLongestTask.get(stageSpanKey);
      sendTaskSpan(span, longestTask);
    }

    Optional<Long> completionTime = Optional.ofNullable(stageCompleted.stageInfo().completionTime().getOrElse(null));
    span.finish(completionTime.orElse(System.currentTimeMillis()) * 1000);
  }

  @Override
  public void onTaskStart(SparkListenerTaskStart taskStart) {}

  @Override
  public void onTaskEnd(SparkListenerTaskEnd taskEnd) {
    int stageId = taskEnd.stageId();
    int stageAttemptId = taskEnd.stageAttemptId();
    int stageSpanKey = stageSpanKey(stageId, stageAttemptId);

    if (stageLongestTask.containsKey(stageSpanKey)) {
      if (taskEnd.taskInfo().duration() > stageLongestTask.get(stageSpanKey).taskInfo().duration()) {
        stageLongestTask.put(stageSpanKey, taskEnd);
      }
    }
    else {
      stageLongestTask.put(stageSpanKey, taskEnd);
    }

    if (!(taskEnd.reason() instanceof TaskFailedReason)) {
      return;
    }
    if (!stageSpans.containsKey(stageSpanKey)) {
      log.warn(String.format("No stage span found for stageId %s", stageId));
      return;
    }

    AgentSpan stageSpan = stageSpans.get(stageSpanKey);
    sendTaskSpan(stageSpan, taskEnd);
  }

  private void sendTaskSpan(AgentSpan stageSpan, SparkListenerTaskEnd taskEnd) {
    AgentSpan taskSpan = tracer
      .buildSpan("task")
      .asChildOf(stageSpan.context())
      .withStartTimestamp(taskEnd.taskInfo().launchTime() * 1000)
      .withTag("poc_spark_span", "true")
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
      .withTag(DDTags.MEASURED, 1)
      .start();

    if (taskEnd.reason() instanceof TaskFailedReason) {
      TaskFailedReason reason = (TaskFailedReason)taskEnd.reason();

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

      availableExecutorTime += (executorRemoved.time() - executor.time()) * executor.executorInfo().totalCores();
      liveExecutors.remove(executor.executorId());
    }
  }

  @Override
  public void onTaskGettingResult(SparkListenerTaskGettingResult taskGettingResult) {}

  @Override
  public void onEnvironmentUpdate(SparkListenerEnvironmentUpdate environmentUpdate) {}

  @Override
  public void onBlockManagerAdded(SparkListenerBlockManagerAdded blockManagerAdded) {}

  @Override
  public void onBlockManagerRemoved(SparkListenerBlockManagerRemoved blockManagerRemoved) {}

  @Override
  public void onUnpersistRDD(SparkListenerUnpersistRDD unpersistRDD) {}

  @Override
  public void onExecutorMetricsUpdate(SparkListenerExecutorMetricsUpdate executorMetricsUpdate) {}

  @Override
  public void onStageExecutorMetrics(SparkListenerStageExecutorMetrics executorMetrics) {}

  @Override
  public void onExecutorBlacklisted(SparkListenerExecutorBlacklisted executorBlacklisted) {}

  @Override
  public void onExecutorExcluded(SparkListenerExecutorExcluded executorExcluded) {}

  @Override
  public void onExecutorBlacklistedForStage(
      SparkListenerExecutorBlacklistedForStage executorBlacklistedForStage) {}

  @Override
  public void onExecutorExcludedForStage(
      SparkListenerExecutorExcludedForStage executorExcludedForStage) {}

  @Override
  public void onNodeBlacklistedForStage(
      SparkListenerNodeBlacklistedForStage nodeBlacklistedForStage) {}

  @Override
  public void onNodeExcludedForStage(SparkListenerNodeExcludedForStage nodeExcludedForStage) {}

  @Override
  public void onExecutorUnblacklisted(SparkListenerExecutorUnblacklisted executorUnblacklisted) {}

  @Override
  public void onExecutorUnexcluded(SparkListenerExecutorUnexcluded executorUnexcluded) {}

  @Override
  public void onNodeBlacklisted(SparkListenerNodeBlacklisted nodeBlacklisted) {}

  @Override
  public void onNodeExcluded(SparkListenerNodeExcluded nodeExcluded) {}

  @Override
  public void onNodeUnblacklisted(SparkListenerNodeUnblacklisted nodeUnblacklisted) {}

  @Override
  public void onNodeUnexcluded(SparkListenerNodeUnexcluded nodeUnexcluded) {}

  @Override
  public void onUnschedulableTaskSetAdded(
      SparkListenerUnschedulableTaskSetAdded unschedulableTaskSetAdded) {}

  @Override
  public void onUnschedulableTaskSetRemoved(
      SparkListenerUnschedulableTaskSetRemoved unschedulableTaskSetRemoved) {}

  @Override
  public void onBlockUpdated(SparkListenerBlockUpdated blockUpdated) {}

  @Override
  public void onSpeculativeTaskSubmitted(SparkListenerSpeculativeTaskSubmitted speculativeTask) {}

  @Override
  public void onOtherEvent(SparkListenerEvent event) {}

  @Override
  public void onResourceProfileAdded(SparkListenerResourceProfileAdded event) {}

  private int stageSpanKey(int stageId, int attemptId) {
    return stageId * 100000 + attemptId;
  }
}
