package listener;

import datadog.trace.api.Trace;
import java.util.concurrent.CompletableFuture;
import org.springframework.messaging.Message;

public class ObservingAsyncErrorHandler
    implements io.awspring.cloud.sqs.listener.errorhandler.AsyncErrorHandler<String> {
  private final ErrorHandlerObservation observation;

  public ObservingAsyncErrorHandler(ErrorHandlerObservation observation) {
    this.observation = observation;
  }

  @Override
  @Trace(operationName = "error.handler", resourceName = "ObservingAsyncErrorHandler.handle")
  public CompletableFuture<Void> handle(Message<String> message, Throwable t) {
    observation.recordAsyncErrorHandler(t);
    return CompletableFuture.completedFuture(null);
  }
}
