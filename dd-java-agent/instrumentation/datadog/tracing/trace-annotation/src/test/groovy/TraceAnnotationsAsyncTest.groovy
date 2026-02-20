import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.bootstrap.instrumentation.api.Tags
import dd.test.trace.annotation.SayTracedHello

import java.util.concurrent.CountDownLatch

class TraceAnnotationsAsyncTest extends InstrumentationSpecification {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.trace.annotation.async", "true")
  }

  def "test span finish with async type support"() {
    setup:
    def latch = new CountDownLatch(1)
    def completableFuture = SayTracedHello.sayHelloFuture(latch)

    expect:
    TEST_WRITER.size() == 0

    when:
    latch.countDown()
    completableFuture.join()

    then:
    assertTraces(1) {
      trace(1) {
        span {
          serviceName "test"
          resourceName "SayTracedHello.sayHelloFuture"
          operationName "trace.annotation"
          tags {
            serviceNameSource null
            defaultTags()
            "$Tags.COMPONENT" "trace"
          }
        }
      }
    }
  }
}
