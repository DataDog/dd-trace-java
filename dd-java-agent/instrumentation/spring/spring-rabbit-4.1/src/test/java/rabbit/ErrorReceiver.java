package rabbit;

import datadog.trace.api.Trace;
import java.util.concurrent.CountDownLatch;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.stereotype.Component;

/**
 * A receiver that always throws an exception, used to test that the instrumentation correctly
 * captures error tags (error.type, error.message, error.stack) on the consumer span when message
 * processing fails.
 */
@Component
public class ErrorReceiver {

  public final CountDownLatch latch = new CountDownLatch(1);

  @Trace(operationName = "receive")
  public void receiveMessage(String message) {
    latch.countDown();
    throw new AmqpRejectAndDontRequeueException("Simulated processing failure");
  }
}
