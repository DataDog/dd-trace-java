import com.datadoghq.sketch.ddsketch.DDSketchProtoBinding
import com.datadoghq.sketch.ddsketch.proto.DDSketch
import com.datadoghq.sketch.ddsketch.store.CollapsingLowestDenseStore
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.instrumentation.spark.DatadogSparkListener
import org.apache.spark.SparkConf
import org.apache.spark.Success$
import org.apache.spark.executor.TaskMetrics
import org.apache.spark.scheduler.JobSucceeded$
import org.apache.spark.scheduler.SparkListenerApplicationEnd
import org.apache.spark.scheduler.SparkListenerApplicationStart
import org.apache.spark.scheduler.SparkListenerExecutorAdded
import org.apache.spark.scheduler.SparkListenerExecutorRemoved
import org.apache.spark.scheduler.SparkListenerJobEnd
import org.apache.spark.scheduler.SparkListenerJobStart
import org.apache.spark.scheduler.SparkListenerStageCompleted
import org.apache.spark.scheduler.SparkListenerStageSubmitted
import org.apache.spark.scheduler.SparkListenerTaskEnd
import org.apache.spark.scheduler.StageInfo
import org.apache.spark.scheduler.TaskInfo
import org.apache.spark.scheduler.TaskLocality
import org.apache.spark.scheduler.cluster.ExecutorInfo
import org.apache.spark.storage.RDDInfo
import scala.Option
import scala.collection.immutable.HashMap
import scala.collection.immutable.Seq

import scala.collection.JavaConverters

class SparkListenerTest extends AgentTestRunner {

  private getTestDatadogSparkListener() {
    def conf = new SparkConf()
    return new DatadogSparkListener(conf, "some_app_id", "some_version")
  }

  private applicationStartEvent(time=0L) {
    // Constructor of SparkListenerApplicationStart changed starting spark 3.0
    if (TestSparkComputation.getSparkVersion() < "3") {
      return new SparkListenerApplicationStart(
        "some_app_name",
        Option.apply("some_app_id"),
        time,
        "some_user",
        Option.apply("1"),
        Option.empty()
        )
    }

    return new SparkListenerApplicationStart(
      "some_app_name",
      Option.apply("some_app_id"),
      time,
      "some_user",
      Option.apply("1"),
      Option.empty(),
      Option.empty()
      )
  }

  private jobStartEvent(Integer jobId, Long time, ArrayList<Integer> stageIds) {
    def stageInfos = stageIds.collect { stageId ->
      createStageInfo(stageId)
    }

    return new SparkListenerJobStart(
      jobId,
      time,
      JavaConverters.asScalaBuffer(stageInfos).toSeq(),
      null
      )
  }

  private createStageInfo(Integer stageId) {
    if (TestSparkComputation.getSparkVersion() < "3") {
      return new StageInfo(
        stageId,
        0,
        "stage_name",
        0,
        JavaConverters.asScalaBuffer([]).toSeq() as Seq<RDDInfo>,
        JavaConverters.asScalaBuffer([]).toSeq() as Seq<Integer>,
        "stage_details",
        null as TaskMetrics,
        null
        )
    }

    return new StageInfo(
      stageId,
      0,
      "stage_name",
      0,
      JavaConverters.asScalaBuffer([]).toSeq() as Seq<RDDInfo>,
      JavaConverters.asScalaBuffer([]).toSeq() as Seq<Integer>,
      "stage_details",
      null as TaskMetrics,
      null,
      Option.apply(),
      0,
      false,
      0
      )
  }

  private jobEndEvent(Integer jobId, Long time) {
    return new SparkListenerJobEnd(jobId, time, JobSucceeded$.MODULE$)
  }

  private stageSubmittedEvent(Integer stageId, Long time) {
    def stageInfo = createStageInfo(stageId)
    stageInfo.submissionTime = Option.apply(time)

    return new SparkListenerStageSubmitted(stageInfo, null)
  }

  private stageCompletedEvent(Integer stageId, Long time) {
    def stageInfo = createStageInfo(stageId)
    stageInfo.completionTime = Option.apply(time)
    return new SparkListenerStageCompleted(stageInfo)
  }

  private taskEndEvent(Integer stageId, Long launchTime, Long executorTime, Long deserializeTime = 0L, Long resultSerializeTime = 0L, Long peakExecutionMemory = 0L) {
    def taskInfo = new TaskInfo(
      0,
      0,
      0,
      launchTime,
      "some_executor_id",
      "some_host",
      TaskLocality.PROCESS_LOCAL(),
      false
      )

    def taskMetrics = new TaskMetrics()
    taskMetrics.setExecutorRunTime(executorTime)
    taskMetrics.setExecutorDeserializeTime(deserializeTime)
    taskMetrics.setResultSerializationTime(resultSerializeTime)
    taskMetrics.incPeakExecutionMemory(peakExecutionMemory)

    if (TestSparkComputation.getSparkVersion() < "3") {
      return new SparkListenerTaskEnd(
        stageId,
        0,
        "task_type",
        Success$.MODULE$,
        taskInfo,
        taskMetrics
        )
    }

    return new SparkListenerTaskEnd(
      stageId,
      0,
      "task_type",
      Success$.MODULE$,
      taskInfo,
      null,
      taskMetrics
      )
  }

  private executorAddedEvent(time=0L, executorId="executor-0", totalCores=0) {
    new SparkListenerExecutorAdded(
      time,
      executorId,
      new ExecutorInfo(
      "some_host",
      totalCores,
      new HashMap<String, String>()
      )
      )
  }

  private executorRemovedEvent(time=0L, executorId="0") {
    new SparkListenerExecutorRemoved(
      time,
      executorId,
      "some_reason"
      )
  }

  def "compute available executor time for the whole application"() {
    setup:
    def listener = getTestDatadogSparkListener()

    listener.onApplicationStart(applicationStartEvent(1000L))

    listener.onExecutorAdded(executorAddedEvent(2000L, "executor-1", 4))
    listener.onExecutorAdded(executorAddedEvent(3000L, "executor-2", 5))
    listener.onExecutorRemoved(executorRemovedEvent(4000L, "executor-2"))

    listener.onApplicationEnd(new SparkListenerApplicationEnd(5000L))

    expect:
    def expectedExecutorTime = (5000 - 2000) * 4 + (4000 - 3000) * 5
    assertTraces(1) {
      trace(1) {
        span {
          operationName "spark.application"
          spanType "spark"
          assert span.tags["spark.available_executor_time"] == expectedExecutorTime
        }
      }
    }
  }

  def "compute available executor time for each stage and job"() {
    setup:
    def listener = getTestDatadogSparkListener()
    listener.onApplicationStart(applicationStartEvent(1000L))

    listener.onExecutorAdded(executorAddedEvent(1100L, "executor-1", 4))
    listener.onJobStart(jobStartEvent(1, 1900L, [1, 2, 3]))
    listener.onStageSubmitted(stageSubmittedEvent(1, 1900L))
    listener.onTaskEnd(taskEndEvent(1, 1900L, 100L, 100L))
    listener.onStageCompleted(stageCompletedEvent(1, 2100L))

    listener.onStageSubmitted(stageSubmittedEvent(2, 2100L))
    listener.onStageSubmitted(stageSubmittedEvent(3, 2100L))

    listener.onTaskEnd(taskEndEvent(2, 2100L, 400L, 0L, 100L))
    listener.onTaskEnd(taskEndEvent(2, 2100L, 500L))
    listener.onTaskEnd(taskEndEvent(3, 2100L, 500L))
    listener.onTaskEnd(taskEndEvent(3, 2100L, 500L))

    listener.onStageCompleted(stageCompletedEvent(2, 2600L))

    listener.onTaskEnd(taskEndEvent(3, 2600L, 400L))
    listener.onTaskEnd(taskEndEvent(3, 2600L, 500L))

    listener.onStageCompleted(stageCompletedEvent(3, 3100L))

    listener.onJobEnd(jobEndEvent(1, 3100L))
    listener.onApplicationEnd(new SparkListenerApplicationEnd(3100L))

    expect:
    /*
     This spark application is composed of one executor of 4 cores available during 2000 units of time.
     The test is generating the following scenario where each character represents 100 units of time
     of a core, that can be executing a task of the corresponding stage id or be idle
     Core1: ........112222233333
     Core2: ..........222223333.
     Core3: ..........33333.....
     Core4: ..........33333.....
     */
    assertTraces(1) {
      trace(5) {
        span {
          operationName "spark.application"
          assert span.tags["spark.available_executor_time"] == 8000L
          assert span.tags["spark.executor_run_time"] == 2900L
          assert span.tags["spark.executor_deserialize_time"] == 100L
          assert span.tags["spark.result_serialization_time"] == 100L
          spanType "spark"
        }
        span {
          operationName "spark.job"
          assert span.tags["spark.available_executor_time"] == 4800L
          assert span.tags["spark.executor_run_time"] == 2900L
          assert span.tags["spark.executor_deserialize_time"] == 100L
          assert span.tags["spark.result_serialization_time"] == 100L
          spanType "spark"
          childOf(span(0))
        }
        span {
          operationName "spark.stage"
          assert span.tags["stage_id"] == 3
          assert span.tags["spark.available_executor_time"] == 3000L
          assert span.tags["spark.executor_run_time"] == 1900L
          assert span.tags["spark.executor_deserialize_time"] == 0L
          assert span.tags["spark.result_serialization_time"] == 0L
          spanType "spark"
          childOf(span(1))
        }
        span {
          operationName "spark.stage"
          assert span.tags["stage_id"] == 2
          assert span.tags["spark.available_executor_time"] == 1000L
          assert span.tags["spark.executor_run_time"] == 900L
          assert span.tags["spark.executor_deserialize_time"] == 0L
          assert span.tags["spark.result_serialization_time"] == 100L
          spanType "spark"
          childOf(span(1))
        }
        span {
          operationName "spark.stage"
          assert span.tags["stage_id"] == 1
          assert span.tags["spark.available_executor_time"] == 800L
          assert span.tags["spark.executor_run_time"] == 100L
          assert span.tags["spark.executor_deserialize_time"] == 100L
          assert span.tags["spark.result_serialization_time"] == 0L
          spanType "spark"
          childOf(span(1))
        }
      }
    }
  }

  def "compute peak execution memory using the max of all stages"() {
    setup:
    def listener = getTestDatadogSparkListener()
    listener.onApplicationStart(applicationStartEvent(1000L))

    listener.onJobStart(jobStartEvent(1, 1000L, [1, 2]))

    listener.onStageSubmitted(stageSubmittedEvent(1, 1000L))
    listener.onTaskEnd(taskEndEvent(1, 1000L, 100L, 0L, 0L, 1000L))
    listener.onTaskEnd(taskEndEvent(1, 1000L, 100L, 0L, 0L, 1200L))
    listener.onStageCompleted(stageCompletedEvent(1, 2000L))

    listener.onStageSubmitted(stageSubmittedEvent(2, 2000L))
    listener.onTaskEnd(taskEndEvent(2, 2000L, 100L, 0L, 0L, 1300L))
    listener.onTaskEnd(taskEndEvent(2, 2000L, 100L, 0L, 0L, 1400L))
    listener.onStageCompleted(stageCompletedEvent(2, 3000L))

    listener.onJobEnd(jobEndEvent(1, 3000L))
    listener.onApplicationEnd(new SparkListenerApplicationEnd(3000L))

    expect:
    assertTraces(1) {
      trace(4) {
        span {
          operationName "spark.application"
          assert span.tags["spark.peak_execution_memory"] == 1400L
          spanType "spark"
        }
        span {
          operationName "spark.job"
          assert span.tags["spark.peak_execution_memory"] == 1400L
          spanType "spark"
          childOf(span(0))
        }
        span {
          operationName "spark.stage"
          assert span.tags["stage_id"] == 2
          assert span.tags["spark.peak_execution_memory"] == 1400L
          spanType "spark"
          childOf(span(1))
        }
        span {
          operationName "spark.stage"
          assert span.tags["stage_id"] == 1
          assert span.tags["spark.peak_execution_memory"] == 1200L
          spanType "spark"
          childOf(span(1))
        }
      }
    }
  }

  def "feature flag for task histograms"() {
    setup:
    injectSysConfig("spark.task-histogram.enabled", "false")
    def listener = getTestDatadogSparkListener()

    listener.onApplicationStart(applicationStartEvent(1000L))
    listener.onJobStart(jobStartEvent(1, 1900L, [1]))
    listener.onStageSubmitted(stageSubmittedEvent(1, 1900L))
    listener.onTaskEnd(taskEndEvent(1,1900L, 300))
    listener.onStageCompleted(stageCompletedEvent(1, 2200L))
    listener.onJobEnd(jobEndEvent(1, 17200L))
    listener.onApplicationEnd(new SparkListenerApplicationEnd(3100L))

    assertTraces(1) {
      trace(3) {
        span {
          operationName "spark.application"
          spanType "spark"
        }
        span {
          operationName "spark.job"
          spanType "spark"
          childOf(span(0))
        }
        span {
          operationName "spark.stage"
          assert span.tags["_dd.spark.task_run_time"] == null
          spanType "spark"
          childOf(span(1))
        }
      }
    }
  }

  def "compute task metrics histograms"() {
    setup:
    def listener = getTestDatadogSparkListener()

    listener.onApplicationStart(applicationStartEvent(1000L))
    listener.onJobStart(jobStartEvent(1, 1900L, [1, 2]))

    listener.onStageSubmitted(stageSubmittedEvent(1, 1900L))
    for(int i = 1; i <= 100; i++) {
      listener.onTaskEnd(taskEndEvent(1,1900L, 0))
    }
    for(int i = 1; i <= 300; i++) {
      listener.onTaskEnd(taskEndEvent(1,1900L, i))
    }
    listener.onStageCompleted(stageCompletedEvent(1, 2200L))

    listener.onStageSubmitted(stageSubmittedEvent(2, 2200L))
    for(int i = 1; i <= 15000; i++) {
      listener.onTaskEnd(taskEndEvent(2, 2200L, i))
    }

    listener.onStageCompleted(stageCompletedEvent(2, 17200L))
    listener.onJobEnd(jobEndEvent(1, 17200L))
    listener.onApplicationEnd(new SparkListenerApplicationEnd(3100L))

    expect:
    def relativeAccuracy = 1/32D
    assertTraces(1) {
      trace(4) {
        span {
          operationName "spark.application"
          validateRelativeError(span.tags["spark.skew_time"] as double, 7700, relativeAccuracy)
          spanType "spark"
        }
        span {
          operationName "spark.job"
          spanType "spark"
          validateRelativeError(span.tags["spark.skew_time"] as double, 7700, relativeAccuracy)
          childOf(span(0))
        }
        span {
          operationName "spark.stage"
          assert span.tags["stage_id"] == 2
          validateRelativeError(span.tags["spark.skew_time"] as double, 7500, relativeAccuracy)
          validateSerializedHistogram(span.tags["_dd.spark.task_run_time"] as String, 7500, 11250, 15000, relativeAccuracy)
          spanType "spark"
          childOf(span(1))
        }
        span {
          operationName "spark.stage"
          assert span.tags["stage_id"] == 1
          validateRelativeError(span.tags["spark.skew_time"] as double, 200, relativeAccuracy)
          validateSerializedHistogram(span.tags["_dd.spark.task_run_time"] as String, 100, 200, 300, relativeAccuracy)
          spanType "spark"
          childOf(span(1))
        }
      }
    }
  }

  private validateRelativeError(double value, double expected, double relativeAccuracy) {
    double relativeError = Math.abs(value - expected) / expected
    assert relativeError < relativeAccuracy
  }

  private validateSerializedHistogram(String base64Hist, double expectedP50, double expectedP75, double expectedMax, double relativeAccuracy) {
    byte[] bytes = Base64.getDecoder().decode(base64Hist)

    def sketch = DDSketchProtoBinding.fromProto({
      new CollapsingLowestDenseStore(512)
    }, DDSketch.parseFrom(bytes))

    validateRelativeError(sketch.getValueAtQuantile(0.5), expectedP50, relativeAccuracy)
    validateRelativeError(sketch.getValueAtQuantile(0.75), expectedP75, relativeAccuracy)
    validateRelativeError(sketch.getMaxValue(), expectedMax, relativeAccuracy)
  }
}
