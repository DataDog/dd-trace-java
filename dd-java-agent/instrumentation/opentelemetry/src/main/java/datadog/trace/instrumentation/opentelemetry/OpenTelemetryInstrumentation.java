package datadog.trace.instrumentation.opentelemetry;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.returns;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.trace.TracerProvider;
import java.util.HashMap;
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
    return named("io.opentelemetry.OpenTelemetry");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".OtelScope",
      packageName + ".OtelSpan",
      packageName + ".OtelSpan$1", // switch statement
      packageName + ".OtelSpanContext",
      packageName + ".OtelTracer",
      packageName + ".OtelTracer$1", // switch statement
      packageName + ".OtelTracerProvider",
      packageName + ".OtelTracer$SpanBuilder",
      packageName + ".OtelContextPropagators",
      packageName + ".OtelContextPropagators$1", // switch statement
      packageName + ".OtelContextPropagators$OtelHttpTextFormat",
      packageName + ".OtelContextPropagators$OtelSetter",
      packageName + ".OtelContextPropagators$OtelGetter",
      packageName + ".TypeConverter",
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    final Map<ElementMatcher<? super MethodDescription>, String> transformers = new HashMap<>();
    transformers.put(
        named("getTracerProvider").and(returns(named("io.opentelemetry.trace.TracerProvider"))),
        OpenTelemetryInstrumentation.class.getName() + "$TracerProviderAdvice");
    transformers.put(
        named("getPropagators")
            .and(returns(named("io.opentelemetry.context.propagation.ContextPropagators"))),
        OpenTelemetryInstrumentation.class.getName() + "$ContextPropagatorsAdvice");
    return transformers;
  }

  public static class TracerProviderAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void returnProvider(@Advice.Return(readOnly = false) TracerProvider result) {
      result = OtelTracerProvider.INSTANCE;
    }
  }

  public static class ContextPropagatorsAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void returnProvider(@Advice.Return(readOnly = false) ContextPropagators result) {
      result = OtelContextPropagators.INSTANCE;
    }

    // Muzzle doesn't detect the advice method's argument type, so we have to help it a bit.
    public static void muzzleCheck(final ContextPropagators propagators) {
      propagators.getHttpTextFormat();
    }
  }
}
