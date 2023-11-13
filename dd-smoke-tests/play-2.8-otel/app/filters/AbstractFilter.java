package filters;

import akka.stream.Materializer;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import play.libs.concurrent.HttpExecutionContext;
import play.mvc.*;

public abstract class AbstractFilter extends Filter {
  private final HttpExecutionContext ec;
  private final String spanName;
  private final boolean wrap;

  public AbstractFilter(String spanName, Materializer mat, HttpExecutionContext ec) {
    this(spanName, false, mat, ec);
  }

  public AbstractFilter(String spanName, boolean wrap, Materializer mat, HttpExecutionContext ec) {
    super(mat);
    this.spanName = spanName;
    this.wrap = wrap;
    this.ec = ec;
  }

  @Override
  public CompletionStage<Result> apply(
      Function<Http.RequestHeader, CompletionStage<Result>> nextFilter,
      Http.RequestHeader requestHeader) {
    final Tracer tracer = GlobalOpenTelemetry.getTracer("play-test");
    final Span startedSpan = wrap ? tracer.spanBuilder(spanName).startSpan() : null;
    Scope outerScope = wrap ? startedSpan.makeCurrent() : null;
    try {
      return nextFilter
          .apply(requestHeader)
          .thenApplyAsync(
              result -> {
                Span span = wrap ? startedSpan : tracer.spanBuilder(spanName).startSpan();
                try (Scope innerScope = span.makeCurrent()) {
                  // Yes this does no real work
                  return result;
                } finally {
                  span.end();
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
