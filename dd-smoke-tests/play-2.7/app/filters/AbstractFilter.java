package filters;

import akka.stream.Materializer;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.util.GlobalTracer;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import play.libs.concurrent.HttpExecutionContext;
import play.mvc.*;

public abstract class AbstractFilter extends Filter {
  private final HttpExecutionContext ec;
  private final String operationName;
  private final boolean wrap;

  public AbstractFilter(String operationName, Materializer mat, HttpExecutionContext ec) {
    this(operationName, false, mat, ec);
  }

  public AbstractFilter(
      String operationName, boolean wrap, Materializer mat, HttpExecutionContext ec) {
    super(mat);
    this.operationName = operationName;
    this.wrap = wrap;
    this.ec = ec;
  }

  @Override
  public CompletionStage<Result> apply(
      Function<Http.RequestHeader, CompletionStage<Result>> nextFilter,
      Http.RequestHeader requestHeader) {
    final Tracer tracer = GlobalTracer.get();
    final Span startedSpan = wrap ? tracer.buildSpan(operationName).start() : null;
    Scope outerScope = wrap ? tracer.scopeManager().activate(startedSpan) : null;
    try {
      return nextFilter
          .apply(requestHeader)
          .thenApplyAsync(
              result -> {
                Span span = wrap ? startedSpan : tracer.buildSpan(operationName).start();
                try (Scope innerScope = tracer.scopeManager().activate(span)) {
                  // Yes this does no real work
                  return result;
                } finally {
                  span.finish();
                }
              },
              ec.current());
    } finally {
      if (wrap) {
        outerScope.close();
      }
    }
  }
}
