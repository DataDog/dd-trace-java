package datadog.trace.instrumentation.spark;

import datadog.trace.bootstrap.instrumentation.api.AgentHistogram;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.nio.ByteBuffer;
import java.util.Base64;
import org.apache.spark.TaskFailedReason;
import org.apache.spark.executor.TaskMetrics;
import org.apache.spark.scheduler.SparkListenerTaskEnd;

class SparkAggregatedTaskMetrics {
  private long executorDeserializeTime = 0L;
  private long executorDeserializeCpuTime = 0L;
  private long executorRunTime = 0L;
  private long executorCpuTime = 0L;
  private long resultSize = 0L;
  private long jvmGCTime = 0L;
  private long resultSerializationTime = 0L;
  private long memoryBytesSpilled = 0L;
  private long diskBytesSpilled = 0L;
  private long peakExecutionMemory = 0L;

  private long inputBytesRead = 0L;
  private long inputRecordsRead = 0L;
  private long outputBytesWritten = 0L;
  private long outputRecordsWritten = 0L;

  private long shuffleReadBytes = 0L;
  private long shuffleReadBytesLocal = 0L;
  private long shuffleReadBytesRemote = 0L;
  private long shuffleReadBytesRemoteToDisk = 0L;
  private long shuffleReadFetchWaitTime = 0L;
  private long shuffleReadRecords = 0L;

  private long shuffleWriteBytes = 0L;
  private long shuffleWriteRecords = 0L;
  private long shuffleWriteTime = 0L;

  private long taskCompletedCount = 0L;
  private long taskFailedCount = 0L;
  private long taskRetriedCount = 0L;
  private long taskWithOutputCount = 0L;

  private long attributedAvailableExecutorTime = 0L;
  private long previousAvailableExecutorTime = 0L;
  private long taskRunTimeSinceLastStage = 0L;
  private long totalTaskRunTimeSinceLastStage = 0L;
  private long skewTime = 0;

  private final AgentHistogram taskRunTimeHistogram = AgentTracer.get().newHistogram(1 / 32.0, 512);
  private final AgentHistogram inputBytesHistogram = AgentTracer.get().newHistogram(1 / 32.0, 512);
  private final AgentHistogram outputBytesHistogram = AgentTracer.get().newHistogram(1 / 32.0, 512);
  private final AgentHistogram shuffleReadBytesHistogram =
      AgentTracer.get().newHistogram(1 / 32.0, 512);
  private final AgentHistogram shuffleWriteBytesHistogram =
      AgentTracer.get().newHistogram(1 / 32.0, 512);
  private final AgentHistogram diskBytesSpilledHistogram =
      AgentTracer.get().newHistogram(1 / 32.0, 512);

  public SparkAggregatedTaskMetrics() {}

  public SparkAggregatedTaskMetrics(long availableExecutorTime) {
    this.previousAvailableExecutorTime = availableExecutorTime;
  }

  public void addTaskMetrics(SparkListenerTaskEnd taskEnd) {
    taskCompletedCount += 1;

    if (taskEnd.taskInfo().attemptNumber() > 0) {
      taskRetriedCount += 1;
    }

    if (taskEnd.reason() instanceof TaskFailedReason) {
      taskFailedCount += 1;
    }

    if (taskEnd.taskMetrics() != null) {
      TaskMetrics taskMetrics = taskEnd.taskMetrics();

      executorDeserializeTime += taskMetrics.executorDeserializeTime();
      executorDeserializeCpuTime += taskMetrics.executorDeserializeCpuTime();
      executorRunTime += taskMetrics.executorRunTime();
      executorCpuTime += taskMetrics.executorCpuTime();
      resultSize += taskMetrics.resultSize();
      jvmGCTime += taskMetrics.jvmGCTime();
      resultSerializationTime += taskMetrics.resultSerializationTime();
      memoryBytesSpilled += taskMetrics.memoryBytesSpilled();
      diskBytesSpilled += taskMetrics.diskBytesSpilled();
      peakExecutionMemory = Math.max(peakExecutionMemory, taskMetrics.peakExecutionMemory());

      inputBytesRead += taskMetrics.inputMetrics().bytesRead();
      inputRecordsRead += taskMetrics.inputMetrics().recordsRead();
      outputBytesWritten += taskMetrics.outputMetrics().bytesWritten();
      outputRecordsWritten += taskMetrics.outputMetrics().recordsWritten();

      shuffleReadBytes += taskMetrics.shuffleReadMetrics().totalBytesRead();
      shuffleReadBytesLocal += taskMetrics.shuffleReadMetrics().localBytesRead();
      shuffleReadBytesRemote += taskMetrics.shuffleReadMetrics().remoteBytesRead();
      shuffleReadBytesRemoteToDisk += taskMetrics.shuffleReadMetrics().remoteBytesReadToDisk();
      shuffleReadFetchWaitTime += taskMetrics.shuffleReadMetrics().fetchWaitTime();
      shuffleReadRecords += taskMetrics.shuffleReadMetrics().recordsRead();

      shuffleWriteBytes += taskMetrics.shuffleWriteMetrics().bytesWritten();
      shuffleWriteRecords += taskMetrics.shuffleWriteMetrics().recordsWritten();
      shuffleWriteTime += taskMetrics.shuffleWriteMetrics().writeTime();

      if (taskMetrics.outputMetrics().recordsWritten() >= 1) {
        taskWithOutputCount += 1;
      }

      long taskRunTime = computeTaskRunTime(taskMetrics);
      taskRunTimeSinceLastStage += taskRunTime;

      taskRunTimeHistogram.accept(taskRunTime);
      inputBytesHistogram.accept(taskMetrics.inputMetrics().bytesRead());
      outputBytesHistogram.accept(taskMetrics.outputMetrics().bytesWritten());
      shuffleReadBytesHistogram.accept(taskMetrics.shuffleReadMetrics().totalBytesRead());
      shuffleWriteBytesHistogram.accept(taskMetrics.shuffleWriteMetrics().bytesWritten());
      diskBytesSpilledHistogram.accept(taskMetrics.diskBytesSpilled());
    }
  }

  public void recordTotalTaskRunTime(long taskRunTime) {
    totalTaskRunTimeSinceLastStage += taskRunTime;
  }

  public void allocateAvailableExecutorTime(long availableExecutorTime) {
    long executorTime = availableExecutorTime - previousAvailableExecutorTime;
    long runTime = taskRunTimeSinceLastStage;
    long totalRunTime = totalTaskRunTimeSinceLastStage;

    if (totalRunTime > 0) {
      double ratio = (double) runTime / totalRunTime;
      attributedAvailableExecutorTime += (long) (ratio * executorTime);
    }

    previousAvailableExecutorTime = availableExecutorTime;
    taskRunTimeSinceLastStage = 0;
    totalTaskRunTimeSinceLastStage = 0;
  }

  public void accumulateStageMetrics(SparkAggregatedTaskMetrics stageMetrics) {
    executorDeserializeTime += stageMetrics.executorDeserializeTime;
    executorDeserializeCpuTime += stageMetrics.executorDeserializeCpuTime;
    executorRunTime += stageMetrics.executorRunTime;
    executorCpuTime += stageMetrics.executorCpuTime;
    resultSize += stageMetrics.resultSize;
    jvmGCTime += stageMetrics.jvmGCTime;
    resultSerializationTime += stageMetrics.resultSerializationTime;
    memoryBytesSpilled += stageMetrics.memoryBytesSpilled;
    diskBytesSpilled += stageMetrics.diskBytesSpilled;
    peakExecutionMemory += stageMetrics.peakExecutionMemory;

    inputBytesRead += stageMetrics.inputBytesRead;
    inputRecordsRead += stageMetrics.inputRecordsRead;
    outputBytesWritten += stageMetrics.outputBytesWritten;
    outputRecordsWritten += stageMetrics.outputRecordsWritten;

    shuffleReadBytes += stageMetrics.shuffleReadBytes;
    shuffleReadBytesLocal += stageMetrics.shuffleReadBytesLocal;
    shuffleReadBytesRemote += stageMetrics.shuffleReadBytesRemote;
    shuffleReadBytesRemoteToDisk += stageMetrics.shuffleReadBytesRemoteToDisk;
    shuffleReadFetchWaitTime += stageMetrics.shuffleReadFetchWaitTime;
    shuffleReadRecords += stageMetrics.shuffleReadRecords;

    shuffleWriteBytes += stageMetrics.shuffleWriteBytes;
    shuffleWriteRecords += stageMetrics.shuffleWriteRecords;
    shuffleWriteTime += stageMetrics.shuffleWriteTime;

    taskCompletedCount += stageMetrics.taskCompletedCount;
    taskFailedCount += stageMetrics.taskFailedCount;
    taskRetriedCount += stageMetrics.taskRetriedCount;
    taskWithOutputCount += stageMetrics.taskWithOutputCount;

    attributedAvailableExecutorTime += stageMetrics.attributedAvailableExecutorTime;
    skewTime += stageMetrics.skewTime;
  }

  public void computeSkew() {
    if (taskRunTimeHistogram.getCount() > 0) {
      double p50 = taskRunTimeHistogram.getValueAtQuantile(0.5);
      double max = taskRunTimeHistogram.getMaxValue();

      skewTime = (long) (max - p50);
    }
  }

  public void setSpanMetrics(AgentSpan span, String prefix) {
    span.setMetric(prefix + ".executor_deserialize_time", executorDeserializeTime);
    span.setMetric(prefix + ".executor_deserialize_cpu_time", executorDeserializeCpuTime);
    span.setMetric(prefix + ".executor_run_time", executorRunTime);
    span.setMetric(prefix + ".executor_cpu_time", executorCpuTime);
    span.setMetric(prefix + ".result_size", resultSize);
    span.setMetric(prefix + ".jvm_gc_time", jvmGCTime);
    span.setMetric(prefix + ".result_serialization_time", resultSerializationTime);
    span.setMetric(prefix + ".memory_bytes_spilled", memoryBytesSpilled);
    span.setMetric(prefix + ".disk_bytes_spilled", diskBytesSpilled);
    span.setMetric(prefix + ".peak_execution_memory", peakExecutionMemory);

    span.setMetric(prefix + ".input_bytes", inputBytesRead);
    span.setMetric(prefix + ".input_records", inputRecordsRead);
    span.setMetric(prefix + ".output_bytes", outputBytesWritten);
    span.setMetric(prefix + ".output_records", outputRecordsWritten);

    span.setMetric(prefix + ".shuffle_read_bytes", shuffleReadBytes);
    span.setMetric(prefix + ".shuffle_read_bytes_local", shuffleReadBytesLocal);
    span.setMetric(prefix + ".shuffle_read_bytes_remote", shuffleReadBytesRemote);
    span.setMetric(prefix + ".shuffle_read_bytes_remote_to_disk", shuffleReadBytesRemoteToDisk);
    span.setMetric(prefix + ".shuffle_read_fetch_wait_time", shuffleReadFetchWaitTime);
    span.setMetric(prefix + ".shuffle_read_records", shuffleReadRecords);

    span.setMetric(prefix + ".shuffle_write_bytes", shuffleWriteBytes);
    span.setMetric(prefix + ".shuffle_write_records", shuffleWriteRecords);
    span.setMetric(prefix + ".shuffle_write_time", shuffleWriteTime);

    span.setMetric(prefix + ".task_completed_count", taskCompletedCount);
    span.setMetric(prefix + ".task_failed_count", taskFailedCount);
    span.setMetric(prefix + ".task_retried_count", taskRetriedCount);
    span.setMetric(prefix + ".task_with_output_count", taskWithOutputCount);

    span.setMetric(prefix + ".available_executor_time", attributedAvailableExecutorTime);
    span.setMetric(prefix + ".skew_time", skewTime);

    if (taskRunTimeHistogram.getCount() > 0) {
      span.setTag("_dd.spark.task_run_time", histogramToBase64(taskRunTimeHistogram));
    }
    if (inputBytesHistogram.getCount() > 0) {
      span.setTag("_dd.spark.input_bytes", histogramToBase64(inputBytesHistogram));
    }
    if (outputBytesHistogram.getCount() > 0) {
      span.setTag("_dd.spark.output_bytes", histogramToBase64(outputBytesHistogram));
    }
    if (shuffleReadBytesHistogram.getCount() > 0) {
      span.setTag("_dd.spark.shuffle_read_bytes", histogramToBase64(shuffleReadBytesHistogram));
    }
    if (shuffleWriteBytesHistogram.getCount() > 0) {
      span.setTag("_dd.spark.shuffle_write_bytes", histogramToBase64(shuffleWriteBytesHistogram));
    }
    if (diskBytesSpilledHistogram.getCount() > 0) {
      span.setTag("_dd.spark.disk_bytes_spilled", histogramToBase64(diskBytesSpilledHistogram));
    }
  }

  public static long computeTaskRunTime(TaskMetrics metrics) {
    return metrics.executorDeserializeTime()
        + metrics.executorRunTime()
        + metrics.resultSerializationTime();
  }

  private static String histogramToBase64(AgentHistogram hist) {
    ByteBuffer bb = hist.serialize();
    byte[] bytes = new byte[bb.remaining()];
    bb.get(bytes);

    return Base64.getEncoder().encodeToString(bytes);
  }
}
