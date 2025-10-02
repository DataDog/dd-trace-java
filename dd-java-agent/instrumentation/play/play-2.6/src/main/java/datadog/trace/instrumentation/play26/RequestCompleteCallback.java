package datadog.trace.instrumentation.play26;

import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromContext;
import static datadog.trace.instrumentation.play26.PlayHttpServerDecorator.DECORATE;
import static datadog.trace.instrumentation.play26.PlayHttpServerDecorator.REPORT_HTTP_STATUS;

import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.api.mvc.Result;
import scala.util.Try;

public class RequestCompleteCallback extends scala.runtime.AbstractFunction1<Try<Result>, Object> {

  private static final Logger log = LoggerFactory.getLogger(RequestCompleteCallback.class);

  private final AgentSpan span;
  private final ContextScope scope;

  public RequestCompleteCallback(final ContextScope scope) {
    this.span = spanFromContext(scope.context());
    this.scope = scope;
  }

  @Override
  public Object apply(final Try<Result> result) {
    try {
      if (result.isFailure()) {
        DECORATE.onError(span, result.failed().get());
      } else {
        Result response = result.get();
        if (REPORT_HTTP_STATUS) {
          DECORATE.onResponse(span, response);
        } else {
          DECORATE.updateOn404Only(span, response);
        }
      }
      DECORATE.beforeFinish(scope.context());
    } catch (final Throwable t) {
      log.debug("error in play instrumentation", t);
    } finally {
      span.finish();
    }
    return null;
  }
}
