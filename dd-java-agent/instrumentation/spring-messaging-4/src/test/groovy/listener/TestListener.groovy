package listener

import io.awspring.cloud.sqs.annotation.SqsListener
import org.springframework.stereotype.Component

@Component
class TestListener {
  @SqsListener(queueNames = "SpringListenerSQS")
  void observe(String message) {
    println "Received $message"
  }
}
