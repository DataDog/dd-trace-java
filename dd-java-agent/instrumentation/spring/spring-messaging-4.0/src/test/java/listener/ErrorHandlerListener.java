package listener;

import io.awspring.cloud.sqs.annotation.SqsListener;
import java.util.concurrent.CompletableFuture;

public class ErrorHandlerListener {

  @SqsListener(queueNames = "SpringListenerSQSError", factory = "sqsListenerContainerFactory")
  public void observeFailure(String message) {
    throw new RuntimeException("listener failure");
  }

  @SqsListener(
      queueNames = "SpringListenerSQSAsyncError",
      factory = "sqsAsyncListenerContainerFactory")
  public CompletableFuture<Void> observeAsyncFailure(String message) {
    throw new RuntimeException("async listener failure");
  }
}
