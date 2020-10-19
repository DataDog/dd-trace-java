package actions;

import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.util.GlobalTracer;
import play.libs.F;
import play.mvc.*;

public abstract class AbstractAction extends Action.Simple {

  private final String operationName;

  protected AbstractAction(String operationName) {
    this.operationName = operationName;
  }

  @Override
  public F.Promise<Result> call(Http.Context context) throws Throwable {
    Tracer tracer = GlobalTracer.get();
    Span span = tracer.buildSpan(operationName).start();
    Scope scope = tracer.scopeManager().activate(span);
    try {
      return delegate.call(context);
    } finally {
      scope.close();
      span.finish();
    }
  }
}
