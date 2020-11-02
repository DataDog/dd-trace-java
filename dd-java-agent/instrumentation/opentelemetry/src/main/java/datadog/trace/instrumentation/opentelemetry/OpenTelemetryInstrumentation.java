package datadog.trace.instrumentation.opentelemetry;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.implementsInterface;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.opentelemetry.api.OpenTelemetryBuilder;
import io.opentelemetry.api.trace.SpanContext;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * This is experimental instrumentation and should only be enabled for evaluation/testing purposes.
 */
@AutoService(Instrumenter.class)
public class OpenTelemetryInstrumentation extends Instrumenter.Default {
  public OpenTelemetryInstrumentation() {
    super("opentelemetry-beta");
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return implementsInterface(named("io.opentelemetry.api.OpenTelemetryBuilder"));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".OtelScope",
      packageName + ".OtelSpan",
      packageName + ".OtelTracer",
      packageName + ".OtelTracerProvider",
      packageName + ".OtelTracer$OtelSpanBuilder",
      packageName + ".OtelContextPropagators",
      packageName + ".OtelContextPropagators$1", // switch statement
      packageName + ".OtelContextPropagators$OtelTextMapPropagator",
      packageName + ".OtelContextPropagators$OtelSetter",
      packageName + ".OtelContextPropagators$OtelGetter",
      packageName + ".TypeConverter",
      packageName + ".TypeConverter$OtelSpanContext"
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        named("build").and(takesNoArguments()),
        OpenTelemetryInstrumentation.class.getName() + "$BuilderAdvice");
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(
        "io.opentelemetry.api.trace.SpanContext", AgentSpan.Context.class.getName());
  }

  public static class BuilderAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void beforeBuild(@Advice.This OpenTelemetryBuilder builder) {
      ContextStore<SpanContext, AgentSpan.Context> spanContextStore =
          InstrumentationContext.get(SpanContext.class, AgentSpan.Context.class);
      builder.setTracerProvider(new OtelTracerProvider(spanContextStore));
      builder.setPropagators(new OtelContextPropagators(spanContextStore));
    }
  }
}
