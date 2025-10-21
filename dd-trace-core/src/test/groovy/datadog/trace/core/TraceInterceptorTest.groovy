package datadog.trace.core

import datadog.trace.TestInterceptor
import datadog.trace.api.GlobalTracer
import datadog.trace.api.config.TracerConfig
import datadog.trace.api.interceptor.MutableSpan
import datadog.trace.api.interceptor.TraceInterceptor
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.test.DDCoreSpecification
import spock.lang.Timeout

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Timeout(10)
class TraceInterceptorTest extends DDCoreSpecification {

  def writer = new ListWriter()
  def tracer

  def setup() {
    injectSysConfig(TracerConfig.TRACE_GIT_METADATA_ENABLED, "false")
    tracer = tracerBuilder().writer(writer).build()
  }

  def cleanup() {
    tracer?.close()
  }

  def "interceptor is registered as a service"() {
    expect:
    tracer.interceptors.iterator().hasNext()
    tracer.interceptors.iterator().next() instanceof TestInterceptor
  }

  def "interceptors with the same priority replaced"() {
    setup:
    int priority = 999
    ((TestInterceptor) tracer.interceptors.iterator().next()).priority = priority
    tracer.interceptors.add(new TraceInterceptor() {
        @Override
        Collection<? extends MutableSpan> onTraceComplete(Collection<? extends MutableSpan> trace) {
          return []
        }

        @Override
        int priority() {
          return priority
        }
      })

    expect:
    tracer.interceptors.size() == 1
    tracer.interceptors.iterator().next() instanceof TestInterceptor
  }

  def "interceptors with different priority sorted"() {
    setup:
    def priority = score
    def existingInterceptor = tracer.interceptors.iterator().next()
    def newInterceptor = new TraceInterceptor() {
        @Override
        Collection<? extends MutableSpan> onTraceComplete(Collection<? extends MutableSpan> trace) {
          return []
        }

        @Override
        int priority() {
          return priority
        }
      }
    tracer.interceptors.add(newInterceptor)

    expect:
    tracer.interceptors == reverse ? [newInterceptor, existingInterceptor]: [existingInterceptor, newInterceptor]

    where:
    score | reverse
    -1    | false
    1     | true
  }

  def "interceptor can discard a trace (p=#score)"() {
    setup:
    def called = new AtomicBoolean(false)
    def latch = new CountDownLatch(1)
    def priority = score
    tracer.interceptors.add(new TraceInterceptor() {
        @Override
        Collection<? extends MutableSpan> onTraceComplete(Collection<? extends MutableSpan> trace) {
          called.set(true)
          latch.countDown()
          return []
        }

        @Override
        int priority() {
          return priority
        }
      })
    tracer.buildSpan("test " + score).start().finish()
    if (score == TestInterceptor.priority) {
      // the interceptor didn't get added, so latch will never be released.
      writer.waitForTraces(1)
    } else {
      latch.await(5, TimeUnit.SECONDS)
    }

    expect:
    tracer.interceptors.size() == expectedSize
    (called.get()) == (score != TestInterceptor.priority)
    (writer == []) == (score != TestInterceptor.priority)

    where:
    score                         | expectedSize| _
    TestInterceptor.priority-1    |            2| _
    TestInterceptor.priority      |            1| _ // This conflicts with TestInterceptor, so it won't be added.
    TestInterceptor.priority+1    |            2| _
  }

  def "interceptor can modify a span"() {
    setup:
    tracer.interceptors.add(new TraceInterceptor() {
        @Override
        Collection<? extends MutableSpan> onTraceComplete(Collection<? extends MutableSpan> trace) {
          for (MutableSpan span : trace) {
            span
              .setOperationName("modifiedON-" + span.getOperationName())
              .setServiceName("modifiedSN-" + span.getServiceName())
              .setResourceName("modifiedRN-" + span.getResourceName())
              .setSpanType("modifiedST-" + span.getSpanType())
              .setTag("boolean-tag", true)
              .setTag("number-tag", 5.0)
              .setTag("string-tag", "howdy")
              .setError(true)
          }
          return trace
        }

        @Override
        int priority() {
          return 1
        }
      })
    tracer.buildSpan("test").start().finish()
    writer.waitForTraces(1)

    expect:
    def trace = writer.firstTrace()
    trace.size() == 1

    def span = trace[0]

    span.context().operationName == "modifiedON-test"
    span.serviceName.startsWith("modifiedSN-")
    span.resourceName.toString() == "modifiedRN-modifiedON-test"
    span.type == "modifiedST-null"
    span.context().getErrorFlag()

    def tags = span.context().tags

    tags["boolean-tag"] == true
    tags["number-tag"] == 5.0
    tags["string-tag"] == "howdy"

    tags["thread.name"] != null
    tags["thread.id"] != null
    tags["runtime-id"] != null
    tags["language"] != null
    tags.size() >= 7
  }

  def "should be robust when interceptor return a null trace"() {
    setup:
    tracer.interceptors.add(new TraceInterceptor() {
        @Override
        Collection<? extends MutableSpan> onTraceComplete(Collection<? extends MutableSpan> trace) {
          null
        }

        @Override
        int priority() {
          return 0
        }
      })

    when:
    DDSpan span = (DDSpan) tracer.startSpan("test", "test")
    span.phasedFinish()
    tracer.write([span])

    then:
    notThrown(Throwable)
  }

  def "register interceptor through bridge"() {
    setup:
    GlobalTracer.registerIfAbsent(tracer)
    def interceptor = new TraceInterceptor() {
        @Override
        Collection<? extends MutableSpan> onTraceComplete(Collection<? extends MutableSpan> trace) {
          return trace
        }

        @Override
        int priority() {
          return 38
        }
      }

    expect:
    GlobalTracer.get().addTraceInterceptor(interceptor)
    tracer.interceptors.contains(interceptor)
  }
}
