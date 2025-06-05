package datadog.trace.instrumentation.opentelemetry;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.returns;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.trace.TracerProvider;
import net.bytebuddy.asm.Advice;

/**
 * This is experimental instrumentation and should only be enabled for evaluation/testing purposes.
 */
@AutoService(InstrumenterModule.class)
public class OpenTelemetryInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public OpenTelemetryInstrumentation() {
    super("opentelemetry-beta");
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  @Override
  public String instrumentedType() {
    return "io.opentelemetry.OpenTelemetry";
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
      packageName + ".OtelContextPropagators$OtelGetter",
      packageName + ".TypeConverter",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("getTracerProvider").and(returns(named("io.opentelemetry.trace.TracerProvider"))),
        OpenTelemetryInstrumentation.class.getName() + "$TracerProviderAdvice");
    transformer.applyAdvice(
        named("getPropagators")
            .and(returns(named("io.opentelemetry.context.propagation.ContextPropagators"))),
        OpenTelemetryInstrumentation.class.getName() + "$ContextPropagatorsAdvice");
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
