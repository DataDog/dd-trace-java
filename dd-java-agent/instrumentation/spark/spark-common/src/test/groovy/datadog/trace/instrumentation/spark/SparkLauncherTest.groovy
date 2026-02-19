package datadog.trace.instrumentation.spark

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.api.sampling.SamplingMechanism
import datadog.trace.bootstrap.instrumentation.api.AgentTracer

class SparkLauncherTest extends InstrumentationSpecification {

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
