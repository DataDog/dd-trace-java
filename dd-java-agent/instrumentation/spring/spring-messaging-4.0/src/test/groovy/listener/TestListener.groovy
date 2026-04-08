package listener

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan

import datadog.trace.core.DDSpan
import io.awspring.cloud.sqs.annotation.SqsListener
import org.springframework.stereotype.Component

import java.util.concurrent.CompletableFuture

@Component
class TestListener extends AsyncObservationSupport {
  @SqsListener(queueNames = "SpringListenerSQS")
  void observe(String message) {
    println "Received $message"
  }

  @SqsListener(queueNames = "SpringListenerSQSAsync")
  CompletableFuture<Void> observeAsync(String message) {
    return CompletableFuture.runAsync {
      recordActiveParentFinished(((DDSpan) activeSpan()).isFinished())
      markAsyncStarted()
      awaitAsyncRelease()
      // Asserting spring.consume root span is active during async execution
      def childSpan = startSpan("async.child")
      def childScope = activateSpan(childSpan)
      childScope.close()
      childSpan.finish()
      println "Async received $message"
    }
  }
}
