package datadog.trace.instrumentation.spark

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.api.sampling.SamplingMechanism
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import org.apache.spark.launcher.SparkAppHandle
import org.apache.spark.launcher.SparkLauncher

class SparkLauncherTest extends InstrumentationSpecification {

  def "StartApplicationAdvice extracts config tags from SparkLauncher"() {
    setup:
    SparkLauncherAdvice.launcherSpan = null

    when:
    def launcher = new SparkLauncher()
      .setMaster("yarn")
      .setDeployMode("cluster")
      .setAppName("test-app")
      .setMainClass("com.example.Main")
      .setAppResource("/path/to/app.jar")
      .setConf("spark.executor.memory", "4g")
      .setConf("spark.executor.instances", "10")
    // Call the advice directly (handle=null, throwable=null simulates a successful call
    // where no SparkAppHandle is returned — span stays open for shutdown hook)
    SparkLauncherAdvice.createLauncherSpan(launcher)
    SparkLauncherAdvice.finishSpan(false, null)

    then:
    assertTraces(1) {
      trace(1) {
        span {
          operationName "spark.launcher.launch"
          spanType "spark"
          resourceName "SparkLauncher.startApplication"
          errored false
          assert span.tags["master"] == "yarn"
          assert span.tags["deploy_mode"] == "cluster"
          assert span.tags["application_name"] == "test-app"
          assert span.tags["main_class"] == "com.example.Main"
          assert span.tags["app_resource"] == "/path/to/app.jar"
          assert span.tags["config.spark_executor_memory"] == "4g"
          assert span.tags["config.spark_executor_instances"] == "10"
        }
      }
    }
  }

  def "StartApplicationAdvice redacts sensitive conf values"() {
    setup:
    SparkLauncherAdvice.launcherSpan = null

    when:
    def launcher = new SparkLauncher()
      // spark.app.name is allowlisted; its value contains "secret" so should be redacted
      .setConf("spark.app.name", "my-secret-app")
      // spark.master is allowlisted; its value is harmless so should pass through
      .setConf("spark.master", "yarn")
    SparkLauncherAdvice.createLauncherSpan(launcher)
    SparkLauncherAdvice.finishSpan(false, null)

    then:
    assertTraces(1) {
      trace(1) {
        span {
          operationName "spark.launcher.launch"
          spanType "spark"
          resourceName "SparkLauncher.startApplication"
          errored false
          assert span.tags["config.spark_app_name"] == "[redacted]"
          assert span.tags["config.spark_master"] == "yarn"
        }
      }
    }
  }

  def "finishSpanWithThrowable finishes span with error"() {
    setup:
    SparkLauncherAdvice.launcherSpan = null

    when:
    def launcher = new SparkLauncher().setAppName("test-app")
    SparkLauncherAdvice.createLauncherSpan(launcher)
    SparkLauncherAdvice.finishSpanWithThrowable(new RuntimeException("startApplication failed"))

    then:
    SparkLauncherAdvice.launcherSpan == null
    assertTraces(1) {
      trace(1) {
        span {
          operationName "spark.launcher.launch"
          spanType "spark"
          errored true
        }
      }
    }
  }

  def "AppHandleListener finishes span on final state FINISHED"() {
    setup:
    SparkLauncherAdvice.launcherSpan = null
    def tracer = AgentTracer.get()
    SparkLauncherAdvice.launcherSpan = tracer
      .buildSpan("spark.launcher.launch")
      .withSpanType("spark")
      .withResourceName("SparkLauncher.startApplication")
      .start()
    SparkLauncherAdvice.launcherSpan.setSamplingPriority(
      PrioritySampling.USER_KEEP,
      SamplingMechanism.DATA_JOBS)
    def listener = new SparkLauncherAdvice.AppHandleListener()
    def handle = Mock(SparkAppHandle)

    when:
    handle.getState() >> SparkAppHandle.State.FINISHED
    handle.getAppId() >> "app-123"
    listener.stateChanged(handle)

    then:
    SparkLauncherAdvice.launcherSpan == null
    assertTraces(1) {
      trace(1) {
        span {
          operationName "spark.launcher.launch"
          spanType "spark"
          errored false
          assert span.tags["spark.app_id"] == "app-123"
          assert span.tags["spark.launcher.app_state"] == "FINISHED"
        }
      }
    }
  }

  def "AppHandleListener finishes span with error on FAILED state"() {
    setup:
    SparkLauncherAdvice.launcherSpan = null
    def tracer = AgentTracer.get()
    SparkLauncherAdvice.launcherSpan = tracer
      .buildSpan("spark.launcher.launch")
      .withSpanType("spark")
      .withResourceName("SparkLauncher.startApplication")
      .start()
    SparkLauncherAdvice.launcherSpan.setSamplingPriority(
      PrioritySampling.USER_KEEP,
      SamplingMechanism.DATA_JOBS)
    def listener = new SparkLauncherAdvice.AppHandleListener()
    def handle = Mock(SparkAppHandle)

    when:
    handle.getState() >> SparkAppHandle.State.FAILED
    handle.getAppId() >> "app-456"
    listener.stateChanged(handle)

    then:
    SparkLauncherAdvice.launcherSpan == null
    assertTraces(1) {
      trace(1) {
        span {
          operationName "spark.launcher.launch"
          spanType "spark"
          errored true
          assert span.tags["error.type"] == "Spark Launcher Failed"
          assert span.tags["error.msg"] == "Application FAILED"
          assert span.tags["spark.app_id"] == "app-456"
        }
      }
    }
  }
}
