package com.datadog.spark;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.apache.spark.executor.TaskMetrics;

class DDSparkTaskMetrics {

  private Long executorDeserializeTime;
  private Long executorDeserializeCpuTime;
  private Long executorRunTime;
  private Long executorCpuTime;
  private Long resultSize;
  private Long jvmGCTime;
  private Long resultSerializationTime;
  private Long memoryBytesSpilled;
  private Long diskBytesSpilled;
  private Long peakExecutionMemory;

  private Long inputBytesRead;
  private Long inputRecordsRead;
  private Long outputBytesWritten;
  private Long outputRecordsWritten;

  private Long shuffleReadBytes;
  private Long shuffleReadBytesLocal;
  private Long shuffleReadBytesRemote;
  private Long shuffleReadBytesRemoteToDisk;
  private Long shuffleReadFetchWaitTime;
  private Long shuffleReadRecords;

  private Long shuffleWriteBytes;
  private Long shuffleWriteRecords;
  private Long shuffleWriteTime;

  public DDSparkTaskMetrics() {
    executorDeserializeTime = 0L;
    executorDeserializeCpuTime = 0L;
    executorRunTime = 0L;
    executorCpuTime = 0L;
    resultSize = 0L;
    jvmGCTime = 0L;
    resultSerializationTime = 0L;
    memoryBytesSpilled = 0L;
    diskBytesSpilled = 0L;
    peakExecutionMemory = 0L;

    inputBytesRead = 0L;
    inputRecordsRead = 0L;
    outputBytesWritten = 0L;
    outputRecordsWritten = 0L;

    shuffleReadBytes = 0L;
    shuffleReadBytesLocal = 0L;
    shuffleReadBytesRemote = 0L;
    shuffleReadBytesRemoteToDisk = 0L;
    shuffleReadFetchWaitTime = 0L;
    shuffleReadRecords = 0L;

    shuffleWriteBytes = 0L;
    shuffleWriteRecords = 0L;
    shuffleWriteTime = 0L;
  }

  public DDSparkTaskMetrics(TaskMetrics taskMetrics) {
    executorDeserializeTime = taskMetrics.executorDeserializeTime();
    executorDeserializeCpuTime = taskMetrics.executorDeserializeCpuTime();
    executorRunTime = taskMetrics.executorRunTime();
    executorCpuTime = taskMetrics.executorCpuTime();
    resultSize = taskMetrics.resultSize();
    jvmGCTime = taskMetrics.jvmGCTime();
    resultSerializationTime = taskMetrics.resultSerializationTime();
    memoryBytesSpilled = taskMetrics.memoryBytesSpilled();
    diskBytesSpilled = taskMetrics.diskBytesSpilled();
    peakExecutionMemory = taskMetrics.peakExecutionMemory();

    inputBytesRead = taskMetrics.inputMetrics().bytesRead();
    inputRecordsRead = taskMetrics.inputMetrics().recordsRead();
    outputBytesWritten = taskMetrics.outputMetrics().bytesWritten();
    outputRecordsWritten = taskMetrics.outputMetrics().recordsWritten();

    shuffleReadBytes = taskMetrics.shuffleReadMetrics().totalBytesRead();
    shuffleReadBytesLocal = taskMetrics.shuffleReadMetrics().localBytesRead();
    shuffleReadBytesRemote = taskMetrics.shuffleReadMetrics().remoteBytesRead();
    shuffleReadBytesRemoteToDisk = taskMetrics.shuffleReadMetrics().remoteBytesReadToDisk();
    shuffleReadFetchWaitTime = taskMetrics.shuffleReadMetrics().fetchWaitTime();
    shuffleReadRecords = taskMetrics.shuffleReadMetrics().recordsRead();

    shuffleWriteBytes = taskMetrics.shuffleWriteMetrics().bytesWritten();
    shuffleWriteRecords = taskMetrics.shuffleWriteMetrics().recordsWritten();
    shuffleWriteTime = taskMetrics.shuffleWriteMetrics().writeTime();
  }

  public void addTaskMetrics(TaskMetrics taskMetrics) {
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
  }

  public void setSpanMetrics(AgentSpan span, String prefix) {
    span.setTag(prefix + ".executor_deserialize_time", executorDeserializeTime);
    span.setTag(prefix + ".executor_deserialize_cpu_time", executorDeserializeCpuTime);
    span.setTag(prefix + ".executor_run_time", executorRunTime);
    span.setTag(prefix + ".executor_cpu_time", executorCpuTime);
    span.setTag(prefix + ".result_size", resultSize);
    span.setTag(prefix + ".jvm_gc_time", jvmGCTime);
    span.setTag(prefix + ".result_serialization_time", resultSerializationTime);
    span.setTag(prefix + ".memory_bytes_spilled", memoryBytesSpilled);
    span.setTag(prefix + ".disk_bytes_spilled", diskBytesSpilled);
    span.setTag(prefix + ".peak_execution_memory", peakExecutionMemory);

    span.setTag(prefix + ".input_bytes", inputBytesRead);
    span.setTag(prefix + ".input_records", inputRecordsRead);
    span.setTag(prefix + ".output_bytes", outputBytesWritten);
    span.setTag(prefix + ".output_records", outputRecordsWritten);

    span.setTag(prefix + ".shuffle_read_bytes", shuffleReadBytes);
    span.setTag(prefix + ".shuffle_read_bytes_local", shuffleReadBytesLocal);
    span.setTag(prefix + ".shuffle_read_bytes_remote", shuffleReadBytesRemote);
    span.setTag(prefix + ".shuffle_read_bytes_remote_to_disk", shuffleReadBytesRemoteToDisk);
    span.setTag(prefix + ".shuffle_read_fetch_wait_time", shuffleReadFetchWaitTime);
    span.setTag(prefix + ".shuffle_read_records", shuffleReadRecords);

    span.setTag(prefix + ".shuffle_write_bytes", shuffleWriteBytes);
    span.setTag(prefix + ".shuffle_write_records", shuffleWriteRecords);
    span.setTag(prefix + ".shuffle_write_time", shuffleWriteTime);
  }
}
