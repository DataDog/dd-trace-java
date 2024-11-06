package datadog.trace.instrumentation.spark;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.util.Properties;
import org.apache.spark.executor.Executor;
import org.apache.spark.executor.TaskMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SparkExecutorDecorator extends BaseDecorator {
  private static final Logger log = LoggerFactory.getLogger(SparkExecutorDecorator.class);

  public static final CharSequence SPARK_TASK = UTF8BytesString.create("spark.task");
  public static final CharSequence SPARK = UTF8BytesString.create("spark");
  public static SparkExecutorDecorator DECORATE = new SparkExecutorDecorator();
  private final String propSparkAppName = "spark.app.name";
  private static final String TASK_DESCRIPTION_CLASSNAME =
      "org.apache.spark.scheduler.TaskDescription";
  private static final MethodHandles.Lookup lookup = MethodHandles.lookup();
  private static final MethodHandle propertiesField_mh;

  private static Class<?> initClass() {
    try {
      return Class.forName(
          SparkExecutorDecorator.TASK_DESCRIPTION_CLASSNAME,
          false,
          SparkExecutorDecorator.class.getClassLoader());
    } catch (ClassNotFoundException e) {
      log.debug("Can't find class '{}'", TASK_DESCRIPTION_CLASSNAME, e);
    }
    return null;
  }

  private static MethodHandle getFieldGetter() {
    Class<?> cls = initClass();

    try {
      if (cls != null) {
        Field field = cls.getDeclaredField("properties");
        field.setAccessible(true);

        return lookup.unreflectGetter(field);
      }

    } catch (NoSuchFieldException | IllegalAccessException e) {
      log.debug("Can't find and unreflect declared field for '{}'", TASK_DESCRIPTION_CLASSNAME);
    }

    return null;
  }

  static {
    propertiesField_mh = getFieldGetter();
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"spark-executor"};
  }

  @Override
  protected CharSequence spanType() {
    return null;
  }

  @Override
  protected CharSequence component() {
    return SPARK;
  }

  public void onTaskStart(AgentSpan span, Executor.TaskRunner taskRunner, Object taskDescription) {
    span.setTag("task_id", taskRunner.taskId());
    span.setTag("task_thread_name", taskRunner.threadName());

    if (taskDescription != null && propertiesField_mh != null) {
      try {
        Properties props = (Properties) propertiesField_mh.invoke(taskDescription);
        if (props != null) {
          String appName = props.getProperty(propSparkAppName);
          if (appName != null) {
            AgentTracer.get()
                .getDataStreamsMonitoring()
                .setThreadServiceName(taskRunner.getThreadId(), appName);
          }
        }
      } catch (Throwable ignored) {
      }
    }
  }

  public void onTaskEnd(AgentSpan span, Executor.TaskRunner taskRunner) {
    AgentTracer.get().getDataStreamsMonitoring().clearThreadServiceName(taskRunner.getThreadId());

    // task is set by spark in run() by deserializing the task binary coming from the driver
    if (taskRunner.task() == null) {
      return;
    }

    span.setTag("stage_id", taskRunner.task().stageId());
    span.setTag("stage_attempt_id", taskRunner.task().stageAttemptId());

    if (taskRunner.task().jobId().isDefined()) {
      span.setTag("job_id", taskRunner.task().jobId().get());
    }
    if (taskRunner.task().appId().isDefined()) {
      span.setTag("app_id", taskRunner.task().appId().get());
    }
    if (taskRunner.task().appAttemptId().isDefined()) {
      span.setTag("app_attempt_id", taskRunner.task().appAttemptId().get());
    }
    span.setTag(
        "application_name", taskRunner.task().localProperties().getProperty(propSparkAppName));

    TaskMetrics metrics = taskRunner.task().metrics();
    span.setMetric("spark.executor_deserialize_time", metrics.executorDeserializeTime());
    span.setMetric("spark.executor_deserialize_cpu_time", metrics.executorDeserializeCpuTime());
    span.setMetric("spark.executor_run_time", metrics.executorRunTime());
    span.setMetric("spark.executor_cpu_time", metrics.executorCpuTime());
    span.setMetric("spark.result_size", metrics.resultSize());
    span.setMetric("spark.jvm_gc_time", metrics.jvmGCTime());
    span.setMetric("spark.result_serialization_time", metrics.resultSerializationTime());
    span.setMetric("spark.memory_bytes_spilled", metrics.memoryBytesSpilled());
    span.setMetric("spark.disk_bytes_spilled", metrics.diskBytesSpilled());
    span.setMetric("spark.peak_execution_memory", metrics.peakExecutionMemory());

    span.setMetric("spark.input_bytes", metrics.inputMetrics().bytesRead());
    span.setMetric("spark.input_records", metrics.inputMetrics().recordsRead());
    span.setMetric("spark.output_bytes", metrics.outputMetrics().bytesWritten());
    span.setMetric("spark.output_records", metrics.outputMetrics().recordsWritten());

    span.setMetric("spark.shuffle_read_bytes", metrics.shuffleReadMetrics().totalBytesRead());
    span.setMetric("spark.shuffle_read_bytes_local", metrics.shuffleReadMetrics().localBytesRead());
    span.setMetric(
        "spark.shuffle_read_bytes_remote", metrics.shuffleReadMetrics().remoteBytesRead());
    span.setMetric(
        "spark.shuffle_read_bytes_remote_to_disk",
        metrics.shuffleReadMetrics().remoteBytesReadToDisk());
    span.setMetric(
        "spark.shuffle_read_fetch_wait_time", metrics.shuffleReadMetrics().fetchWaitTime());
    span.setMetric("spark.shuffle_read_records", metrics.shuffleReadMetrics().recordsRead());

    span.setMetric("spark.shuffle_write_bytes", metrics.shuffleWriteMetrics().bytesWritten());
    span.setMetric("spark.shuffle_write_records", metrics.shuffleWriteMetrics().recordsWritten());
    span.setMetric("spark.shuffle_write_time", metrics.shuffleWriteMetrics().writeTime());
  }
}
