package actions;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.util.concurrent.CompletionStage;
import play.mvc.*;

public abstract class AbstractAction extends Action.Simple {

  private final String operationName;

  protected AbstractAction(String operationName) {
    this.operationName = operationName;
  }

  @Override
  public CompletionStage<Result> call(Http.Request req) {
    Tracer tracer = GlobalOpenTelemetry.getTracer("play-test");
    Span span = tracer.spanBuilder(operationName).startSpan();
    Scope scope = span.makeCurrent();
    try {
      return delegate.call(req);
    } finally {
      scope.close();
      span.end();
    }
  }
}
