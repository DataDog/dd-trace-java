import static annotatedsample.ReactiveStreamsTracedMethods.TestPublisher.ofComplete
import static annotatedsample.ReactiveStreamsTracedMethods.TestPublisher.ofFailing
import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

import datadog.trace.agent.test.InstrumentationSpecification
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription

import java.util.concurrent.CountDownLatch

class PublishSubscribeTest extends InstrumentationSpecification {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("trace.otel.enabled", "true")
  }

  def "test publish / subscribe with publisher #publisher.class"() {
    when:
    runUnderTrace("parent") {
      publisher.subscribe(new Subscriber<String>() {
          @Override
          void onSubscribe(Subscription subscription) {
            assert TEST_TRACER.activeSpan() != null
            subscription.request(1)
          }

          @Override
          void onNext(String s) {
            assert TEST_TRACER.activeSpan() != null
          }

          @Override
          void onError(Throwable throwable) {
            runUnderTrace("child", {})
          }

          @Override
          void onComplete() {
            runUnderTrace("child", {})
          }
        })
    }

    then:
    assertTraces(1) {
      trace(2) {
        sortSpansByStart()
        basicSpan(it, "parent")
        basicSpan(it, "child", span(0))
      }
    }
    where:
    publisher                                             | _
    ofComplete(new CountDownLatch(0), "")                 | _
    ofFailing(new CountDownLatch(0), new Exception())     | _
  }
}
