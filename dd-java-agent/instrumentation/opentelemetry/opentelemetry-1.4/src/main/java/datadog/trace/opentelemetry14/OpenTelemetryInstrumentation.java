package datadog.trace.opentelemetry14;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import io.opentelemetry.api.trace.TracerProvider;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class OpenTelemetryInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.CanShortcutTypeMatching {
  public OpenTelemetryInstrumentation() {
    super("opentelemetry", "opentelemetry-1");
  }

  @Override
  public String hierarchyMarkerType() {
    return "io.opentelemetry.api.OpenTelemetry";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "io.opentelemetry.api.DefaultOpenTelemetry",
      "io.opentelemetry.api.GlobalOpenTelemetry$ObfuscatedOpenTelemetry"
    };
  }

  @Override
  public boolean onlyMatchKnownTypes() {
    return isShortcutMatchingEnabled(false);
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".OtelScope",
      packageName + ".OtelSpan",
      packageName + ".OtelSpanBuilder",
      packageName + ".OtelSpanContext",
      packageName + ".OtelTracer",
      packageName + ".OtelTracerBuilder",
      packageName + ".OtelTracerProvider",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    // TracerProvider.getTracerProvider()
    transformation.applyAdvice(
        isMethod()
            .and(named("getTracerProvider"))
            .and(takesNoArguments())
            .and(returns(named("io.opentelemetry.api.trace.TracerProvider"))),
        OpenTelemetryInstrumentation.class.getName() + "$TracerProviderAdvice");
  }

  public static class TracerProviderAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void returnProvider(@Advice.Return(readOnly = false) TracerProvider result) {
      result = OtelTracerProvider.INSTANCE;
    }
  }
}
