package datadog.trace.instrumentation.springweb6;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.ErrorPriorities;
import net.bytebuddy.asm.Advice;

public class ErrorHandlerAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void nameResource(@Advice.Argument(3) final Exception exception) {
    final AgentSpan span = activeSpan();
    if (span != null && exception != null) {
      boolean alreadyError = span.isError();
      SpringWebHttpServerDecorator.DECORATE.onError(span, exception);
      // We want to capture the stacktrace, but that doesn't mean it should be an error.
      // We rely on a decorator to set the error state based on response code. (5xx -> error)
      // Status code might not be set though if the span isn't the server span.
      // Meaning the error won't be set by the status code. (Probably ok since not measured.)
      span.setError(alreadyError, ErrorPriorities.HTTP_SERVER_DECORATOR);
    }
  }
}
