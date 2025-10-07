package executor

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.bootstrap.instrumentation.api.AgentTracer

import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicBoolean

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class ContextLeakTest extends InstrumentationSpecification {

  def "trace should not leak into TPE Worker task when '#name'"() {
    setup:
    AtomicBoolean parentSpanActive = new AtomicBoolean(false)
    def tpe = Executors.newFixedThreadPool(1) as ThreadPoolExecutor

    when: "tpe prestarted under an active trace"
    runUnderTrace("span") {
      interaction(tpe)
    }
    // submit a probe to check if the span is active or not
    tpe.submit {
      parentSpanActive.set(AgentTracer.activeSpan()?.operationName == "span")
    }.get()

    then: "the prestart time span did not leak into the worker"
    !parentSpanActive.get()

    where:
    name                         | interaction
    "prestart all core threads"  | { e -> assert e.prestartAllCoreThreads() > 0 : "threads already started" }
    "prestart core thread"       | { e -> assert e.prestartCoreThread() : "thread already started" }
    "lazy init worker"           | { e -> e.execute {} }
  }
}
