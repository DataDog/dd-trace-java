package datadog.trace.instrumentation.springweb7;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.ErrorPriorities;
import net.bytebuddy.asm.Advice;

/**
 * Captures the exception stacktrace on the active span without marking it as an error. The error
 * state is determined by the HTTP status code decorator (5xx → error), not by the presence of an
 * exception. If the span is not the server span, the status code may not be set, so the error state
 * remains unchanged.
 */
public class ErrorHandlerAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void nameResource(@Advice.Argument(3) final Exception exception) {
    final AgentSpan span = activeSpan();
    if (span != null && exception != null) {
      boolean alreadyError = span.isError();
      SpringWebHttpServerDecorator.DECORATE.onError(span, exception);
      span.setError(alreadyError, ErrorPriorities.HTTP_SERVER_DECORATOR);
    }
  }
}
