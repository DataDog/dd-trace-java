package com.datadog.spark;

import datadog.trace.bootstrap.instrumentation.spark.SparkAgentContext;
import org.apache.spark.SparkConf;
import org.apache.spark.scheduler.SparkListenerApplicationEnd;
import org.apache.spark.scheduler.SparkListenerApplicationStart;
import org.apache.spark.scheduler.SparkListenerBlockManagerAdded;
import org.apache.spark.scheduler.SparkListenerBlockManagerRemoved;
import org.apache.spark.scheduler.SparkListenerBlockUpdated;
import org.apache.spark.scheduler.SparkListenerEnvironmentUpdate;
import org.apache.spark.scheduler.SparkListenerEvent;
import org.apache.spark.scheduler.SparkListenerExecutorAdded;
import org.apache.spark.scheduler.SparkListenerExecutorBlacklisted;
import org.apache.spark.scheduler.SparkListenerExecutorBlacklistedForStage;
import org.apache.spark.scheduler.SparkListenerExecutorExcluded;
import org.apache.spark.scheduler.SparkListenerExecutorExcludedForStage;
import org.apache.spark.scheduler.SparkListenerExecutorMetricsUpdate;
import org.apache.spark.scheduler.SparkListenerExecutorRemoved;
import org.apache.spark.scheduler.SparkListenerExecutorUnblacklisted;
import org.apache.spark.scheduler.SparkListenerExecutorUnexcluded;
import org.apache.spark.scheduler.SparkListenerInterface;
import org.apache.spark.scheduler.SparkListenerJobEnd;
import org.apache.spark.scheduler.SparkListenerJobStart;
import org.apache.spark.scheduler.SparkListenerNodeBlacklisted;
import org.apache.spark.scheduler.SparkListenerNodeBlacklistedForStage;
import org.apache.spark.scheduler.SparkListenerNodeExcluded;
import org.apache.spark.scheduler.SparkListenerNodeExcludedForStage;
import org.apache.spark.scheduler.SparkListenerNodeUnblacklisted;
import org.apache.spark.scheduler.SparkListenerNodeUnexcluded;
import org.apache.spark.scheduler.SparkListenerResourceProfileAdded;
import org.apache.spark.scheduler.SparkListenerSpeculativeTaskSubmitted;
import org.apache.spark.scheduler.SparkListenerStageCompleted;
import org.apache.spark.scheduler.SparkListenerStageExecutorMetrics;
import org.apache.spark.scheduler.SparkListenerStageSubmitted;
import org.apache.spark.scheduler.SparkListenerTaskEnd;
import org.apache.spark.scheduler.SparkListenerTaskGettingResult;
import org.apache.spark.scheduler.SparkListenerTaskStart;
import org.apache.spark.scheduler.SparkListenerUnpersistRDD;
import org.apache.spark.scheduler.SparkListenerUnschedulableTaskSetAdded;
import org.apache.spark.scheduler.SparkListenerUnschedulableTaskSetRemoved;

public class DDSparkListener implements SparkListenerInterface {

  public DDSparkListener(SparkConf sparkConf) {
    System.err.println("==DDSparkListener created!==");
  }

  @Override
  public void onStageCompleted(SparkListenerStageCompleted stageCompleted) {
    System.err.println("onStageCompleted");
    SparkAgentContext.sendMetrics(42);
  }

  @Override
  public void onStageSubmitted(SparkListenerStageSubmitted stageSubmitted) {
    System.err.println("onStageSubmitted");
  }

  @Override
  public void onTaskStart(SparkListenerTaskStart taskStart) {
    System.err.println("onStageSubmitted");
  }

  @Override
  public void onTaskGettingResult(SparkListenerTaskGettingResult taskGettingResult) {
    System.err.println("onTaskGettingResult");
  }

  @Override
  public void onTaskEnd(SparkListenerTaskEnd taskEnd) {
    System.err.println("onTaskEnd");
  }

  @Override
  public void onJobStart(SparkListenerJobStart jobStart) {
    System.err.println("onTaskEnd");
  }

  @Override
  public void onJobEnd(SparkListenerJobEnd jobEnd) {
    System.err.println("onJobEnd");
  }

  @Override
  public void onEnvironmentUpdate(SparkListenerEnvironmentUpdate environmentUpdate) {
    System.err.println("onEnvironmentUpdate");
  }

  @Override
  public void onBlockManagerAdded(SparkListenerBlockManagerAdded blockManagerAdded) {
    System.err.println("onBlockManagerAdded");
  }

  @Override
  public void onBlockManagerRemoved(SparkListenerBlockManagerRemoved blockManagerRemoved) {}

  @Override
  public void onUnpersistRDD(SparkListenerUnpersistRDD unpersistRDD) {}

  @Override
  public void onApplicationStart(SparkListenerApplicationStart applicationStart) {}

  @Override
  public void onApplicationEnd(SparkListenerApplicationEnd applicationEnd) {}

  @Override
  public void onExecutorMetricsUpdate(SparkListenerExecutorMetricsUpdate executorMetricsUpdate) {}

  @Override
  public void onStageExecutorMetrics(SparkListenerStageExecutorMetrics executorMetrics) {}

  @Override
  public void onExecutorAdded(SparkListenerExecutorAdded executorAdded) {}

  @Override
  public void onExecutorRemoved(SparkListenerExecutorRemoved executorRemoved) {}

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
}
