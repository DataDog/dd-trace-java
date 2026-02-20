package datadog.trace.instrumentation.spark

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.api.sampling.SamplingMechanism
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import org.apache.spark.launcher.SparkLauncher

class SparkLauncherTest extends InstrumentationSpecification {

  def "createLauncherSpan extracts config tags from SparkLauncher"() {
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
    SparkLauncherAdvice.createLauncherSpan("SparkLauncher.startApplication", launcher)
    SparkLauncherAdvice.finishLauncherSpan(0)

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

  def "createLauncherSpan redacts sensitive conf values"() {
    setup:
    SparkLauncherAdvice.launcherSpan = null

    when:
    def launcher = new SparkLauncher()
      // spark.app.name is allowlisted; its value contains "secret" so should be redacted
      .setConf("spark.app.name", "my-secret-app")
      // spark.master is allowlisted; its value is harmless so should pass through
      .setConf("spark.master", "yarn")
    SparkLauncherAdvice.createLauncherSpan("SparkLauncher.startApplication", launcher)
    SparkLauncherAdvice.finishLauncherSpan(0)

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

  def "generate spark.launcher span from startApplication"() {
    setup:
    SparkLauncherAdvice.launcherSpan = null

    when:
    def tracer = AgentTracer.get()
    def launcherSpan = tracer
      .buildSpan("spark.launcher")
      .withSpanType("spark")
      .withResourceName("SparkLauncher.startApplication")
      .start()
    launcherSpan.setSamplingPriority(
      PrioritySampling.USER_KEEP,
      SamplingMechanism.DATA_JOBS)
    SparkLauncherAdvice.launcherSpan = launcherSpan

    SparkLauncherAdvice.finishLauncherSpan(1)

    then:
    assertTraces(1) {
      trace(1) {
        span {
          operationName "spark.launcher"
          spanType "spark"
          resourceName "SparkLauncher.startApplication"
          errored true
          assert span.tags["error.type"] == "Spark Launcher Failed with exit code 1"
        }
      }
    }
  }

  def "generate spark.launcher span with successful exit"() {
    setup:
    SparkLauncherAdvice.launcherSpan = null

    when:
    def tracer = AgentTracer.get()
    def launcherSpan = tracer
      .buildSpan("spark.launcher")
      .withSpanType("spark")
      .withResourceName("SparkLauncher.launch")
      .start()
    launcherSpan.setSamplingPriority(
      PrioritySampling.USER_KEEP,
      SamplingMechanism.DATA_JOBS)
    SparkLauncherAdvice.launcherSpan = launcherSpan

    SparkLauncherAdvice.finishLauncherSpan(0)

    then:
    assertTraces(1) {
      trace(1) {
        span {
          operationName "spark.launcher"
          spanType "spark"
          resourceName "SparkLauncher.launch"
          errored false
        }
      }
    }
  }
}
