package actions;

import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.util.GlobalTracer;
import java.util.concurrent.CompletionStage;
import play.mvc.*;

public abstract class AbstractAction extends Action.Simple {

  private final String operationName;

  protected AbstractAction(String operationName) {
    this.operationName = operationName;
  }

  @Override
  public CompletionStage<Result> call(Http.Request req) {
    Tracer tracer = GlobalTracer.get();
    Span span = tracer.buildSpan(operationName).start();
    Scope scope = tracer.scopeManager().activate(span);
    try {
      return delegate.call(req);
    } finally {
      scope.close();
      span.finish();
    }
  }
}
