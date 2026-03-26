package listener

import io.awspring.cloud.sqs.annotation.SqsListener
import org.springframework.stereotype.Component

import java.util.concurrent.CompletableFuture

@Component
class TestListener {
  @SqsListener(queueNames = "SpringListenerSQS")
  void observe(String message) {
    println "Received $message"
  }

  @SqsListener(queueNames = "SpringListenerSQSAsync")
  CompletableFuture<Void> observeAsync(String message) {
    return CompletableFuture.runAsync {
      Thread.sleep(500)
      println "Async received $message"
    }
  }
}
