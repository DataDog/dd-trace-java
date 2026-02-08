package datadog.trace.instrumentation.spark;

import com.fasterxml.jackson.core.JsonGenerator;
import datadog.metrics.api.Histogram;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.spark.TaskFailedReason;
import org.apache.spark.executor.TaskMetrics;
import org.apache.spark.scheduler.SparkListenerTaskEnd;
import org.apache.spark.sql.execution.metric.SQLMetricInfo;
import org.apache.spark.util.AccumulatorV2;

class SparkAggregatedTaskMetrics {
  private static final double HISTOGRAM_RELATIVE_ACCURACY = 1 / 32.0;
  private static final int HISTOGRAM_MAX_NUM_BINS = 512;
  private final boolean isSparkTaskHistogramEnabled = Config.get().isSparkTaskHistogramEnabled();

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

  private Histogram taskRunTimeHistogram;
  private Histogram inputBytesHistogram;
  private Histogram outputBytesHistogram;
  private Histogram shuffleReadBytesHistogram;
  private Histogram shuffleWriteBytesHistogram;
  private Histogram diskBytesSpilledHistogram;

  // Used for Spark SQL Plan metrics ONLY, don't put in regular span for now
  private Map<Long, Histogram> externalAccumulableHistograms;

  public SparkAggregatedTaskMetrics() {}

  public SparkAggregatedTaskMetrics(long availableExecutorTime) {
    this.previousAvailableExecutorTime = availableExecutorTime;
  }

  public void addTaskMetrics(
      SparkListenerTaskEnd taskEnd, List<AccumulatorV2> externalAccumulators) {
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

      if (isSparkTaskHistogramEnabled) {
        taskRunTimeHistogram = lazyHistogramAccept(taskRunTimeHistogram, taskRunTime);
        inputBytesHistogram =
            lazyHistogramAccept(inputBytesHistogram, taskMetrics.inputMetrics().bytesRead());
        outputBytesHistogram =
            lazyHistogramAccept(outputBytesHistogram, taskMetrics.outputMetrics().bytesWritten());
        shuffleReadBytesHistogram =
            lazyHistogramAccept(
                shuffleReadBytesHistogram, taskMetrics.shuffleReadMetrics().totalBytesRead());
        shuffleWriteBytesHistogram =
            lazyHistogramAccept(
                shuffleWriteBytesHistogram, taskMetrics.shuffleWriteMetrics().bytesWritten());
        diskBytesSpilledHistogram =
            lazyHistogramAccept(diskBytesSpilledHistogram, taskMetrics.diskBytesSpilled());

        // TODO (CY): Should we also look at TaskInfo accumulable update values as a backup? Is that
        // only needed for SHS?
        if (externalAccumulators != null && !externalAccumulators.isEmpty()) {
          if (externalAccumulableHistograms == null) {
            externalAccumulableHistograms = new HashMap<>(externalAccumulators.size());
          }

          externalAccumulators.forEach(
              acc -> {
                Histogram hist = externalAccumulableHistograms.get(acc.id());
                if (hist == null) {
                  hist =
                      Histogram.newHistogram(HISTOGRAM_RELATIVE_ACCURACY, HISTOGRAM_MAX_NUM_BINS);
                }

                try {
                  // As of spark 3.5, all SQL metrics are Long, safeguard if it changes in new
                  // versions
                  hist.accept((Long) acc.value());
                  externalAccumulableHistograms.put(acc.id(), hist);
                } catch (ClassCastException ignored) {
                }
              });
        }
      }
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
    peakExecutionMemory = Math.max(stageMetrics.peakExecutionMemory, peakExecutionMemory);

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
    if (taskRunTimeHistogram != null && taskRunTimeHistogram.getCount() > 0) {
      double p50 = taskRunTimeHistogram.getValueAtQuantile(0.5);
      double max = taskRunTimeHistogram.getMaxValue();

      skewTime = (long) (max - p50);
    }
  }

  public void setSpanMetrics(AgentSpan span) {
    span.setMetric("spark.executor_deserialize_time", executorDeserializeTime);
    span.setMetric("spark.executor_deserialize_cpu_time", executorDeserializeCpuTime);
    span.setMetric("spark.executor_run_time", executorRunTime);
    span.setMetric("spark.executor_cpu_time", executorCpuTime);
    span.setMetric("spark.result_size", resultSize);
    span.setMetric("spark.jvm_gc_time", jvmGCTime);
    span.setMetric("spark.result_serialization_time", resultSerializationTime);
    span.setMetric("spark.memory_bytes_spilled", memoryBytesSpilled);
    span.setMetric("spark.disk_bytes_spilled", diskBytesSpilled);
    span.setMetric("spark.peak_execution_memory", peakExecutionMemory);

    span.setMetric("spark.input_bytes", inputBytesRead);
    span.setMetric("spark.input_records", inputRecordsRead);
    span.setMetric("spark.output_bytes", outputBytesWritten);
    span.setMetric("spark.output_records", outputRecordsWritten);

    span.setMetric("spark.shuffle_read_bytes", shuffleReadBytes);
    span.setMetric("spark.shuffle_read_bytes_local", shuffleReadBytesLocal);
    span.setMetric("spark.shuffle_read_bytes_remote", shuffleReadBytesRemote);
    span.setMetric("spark.shuffle_read_bytes_remote_to_disk", shuffleReadBytesRemoteToDisk);
    span.setMetric("spark.shuffle_read_fetch_wait_time", shuffleReadFetchWaitTime);
    span.setMetric("spark.shuffle_read_records", shuffleReadRecords);

    span.setMetric("spark.shuffle_write_bytes", shuffleWriteBytes);
    span.setMetric("spark.shuffle_write_records", shuffleWriteRecords);
    span.setMetric("spark.shuffle_write_time", shuffleWriteTime);

    span.setMetric("spark.task_completed_count", taskCompletedCount);
    span.setMetric("spark.task_failed_count", taskFailedCount);
    span.setMetric("spark.task_retried_count", taskRetriedCount);
    span.setMetric("spark.task_with_output_count", taskWithOutputCount);

    span.setMetric("spark.available_executor_time", attributedAvailableExecutorTime);
    span.setMetric("spark.skew_time", skewTime);

    if (taskRunTimeHistogram != null && taskRunTimeHistogram.getCount() > 0) {
      span.setTag("_dd.spark.task_run_time", histogramToBase64(taskRunTimeHistogram));
    }
    if (inputBytesHistogram != null && inputBytesHistogram.getCount() > 0) {
      span.setTag("_dd.spark.input_bytes", histogramToBase64(inputBytesHistogram));
    }
    if (outputBytesHistogram != null && outputBytesHistogram.getCount() > 0) {
      span.setTag("_dd.spark.output_bytes", histogramToBase64(outputBytesHistogram));
    }
    if (shuffleReadBytesHistogram != null && shuffleReadBytesHistogram.getCount() > 0) {
      span.setTag("_dd.spark.shuffle_read_bytes", histogramToBase64(shuffleReadBytesHistogram));
    }
    if (shuffleWriteBytesHistogram != null && shuffleWriteBytesHistogram.getCount() > 0) {
      span.setTag("_dd.spark.shuffle_write_bytes", histogramToBase64(shuffleWriteBytesHistogram));
    }
    if (diskBytesSpilledHistogram != null && diskBytesSpilledHistogram.getCount() > 0) {
      span.setTag("_dd.spark.disk_bytes_spilled", histogramToBase64(diskBytesSpilledHistogram));
    }
  }

  /**
   * Lazy creation of histograms, only creating one if there is a non-zero value. Spark stages
   * usually involve either input/output or shuffle read/write operations, resulting in an average
   * of 3 histograms having non-zero values
   */
  private Histogram lazyHistogramAccept(Histogram hist, double value) {
    if (hist != null) {
      hist.accept(value);
    } else {
      if (value != 0) {
        // All the callbacks in DatadogSparkListener are called from the same thread, meaning we
        // don't risk to lose values by creating the histogram this way
        hist = Histogram.newHistogram(HISTOGRAM_RELATIVE_ACCURACY, HISTOGRAM_MAX_NUM_BINS);
        if (taskCompletedCount > 1) {
          // Filling all the previous 0s that we might have missed
          hist.accept(0, taskCompletedCount - 1);
        }
        hist.accept(value);
      }
    }

    return hist;
  }

  // Used to put external accum metrics to JSON for Spark SQL plans
  public void externalAccumToJson(JsonGenerator generator, SQLMetricInfo info) throws IOException {
    if (externalAccumulableHistograms != null) {
      Histogram hist = externalAccumulableHistograms.get(info.accumulatorId());
      String name = info.name();

      if (name != null && hist != null) {
        generator.writeStartObject();
        generator.writeStringField(name, histogramToBase64(hist));
        generator.writeStringField("type", info.metricType());
        generator.writeEndObject();
      }
    }
  }

  public static long computeTaskRunTime(TaskMetrics metrics) {
    return metrics.executorDeserializeTime()
        + metrics.executorRunTime()
        + metrics.resultSerializationTime();
  }

  private static String histogramToBase64(Histogram hist) {
    ByteBuffer bb = hist.serialize();

    byte[] bytes;
    if (bb.hasArray()) {
      bytes = bb.array();
    } else {
      bytes = new byte[bb.remaining()];
      bb.get(bytes);
    }

    return Base64.getEncoder().encodeToString(bytes);
  }
}
