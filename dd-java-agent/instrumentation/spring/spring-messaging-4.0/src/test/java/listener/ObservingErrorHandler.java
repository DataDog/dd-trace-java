package listener;

import datadog.trace.api.Trace;
import org.springframework.messaging.Message;

public class ObservingErrorHandler
    implements io.awspring.cloud.sqs.listener.errorhandler.ErrorHandler<String> {
  private final ErrorHandlerObservation observation;

  public ObservingErrorHandler(ErrorHandlerObservation observation) {
    this.observation = observation;
  }

  @Override
  @Trace(operationName = "error.handler", resourceName = "ObservingErrorHandler.handle")
  public void handle(Message<String> message, Throwable t) {
    observation.recordBlockingErrorHandler(t);
  }
}
