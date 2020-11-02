package datadog.trace.instrumentation.opentelemetry.context;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.instrumentation.opentelemetry.TypeConverter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import net.bytebuddy.asm.Advice;

public class ContextStorageAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void updateScope(
      @Advice.Argument(0) Context context, @Advice.Return(readOnly = false) Scope scope) {
    // Get the span from the current context.
    Span span = Span.fromContext(context);
    // Activate the span so our context can be kept in sync.
    AgentScope agentScope = activateSpan(TypeConverter.toAgentSpan(span));
    // Since the default returned Scope instance is an anonymous class, wrapping should be fine.
    scope = new WrappedScope(scope, agentScope);
  }

  // Keep in sync with OpenTelemetryInstrumentation
  public static void muzzleCheck(final SpanBuilder builder) {
    builder.startSpan();
  }
}
