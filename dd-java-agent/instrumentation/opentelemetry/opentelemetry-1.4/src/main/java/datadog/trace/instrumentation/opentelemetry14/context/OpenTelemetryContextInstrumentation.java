package datadog.trace.instrumentation.opentelemetry14.context;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.opentelemetry14.OpenTelemetryInstrumentation.ROOT_PACKAGE_NAME;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.InstrumenterConfig;
import io.opentelemetry.context.Context;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class OpenTelemetryContextInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.CanShortcutTypeMatching {

  public OpenTelemetryContextInstrumentation() {
    super("opentelemetry.experimental", "opentelemetry-1");
  }

  @Override
  protected boolean defaultEnabled() {
    return InstrumenterConfig.get().isTraceOtelEnabled();
  }

  @Override
  public String hierarchyMarkerType() {
    return "io.opentelemetry.context.Context";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "io.opentelemetry.context.ArrayBasedContext",
    };
  }

  @Override
  public boolean onlyMatchKnownTypes() {
    return isShortcutMatchingEnabled(false);
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".OtelContext",
      packageName + ".OtelScope",
      ROOT_PACKAGE_NAME + ".trace.OtelExtractedContext",
      ROOT_PACKAGE_NAME + ".trace.OtelConventions",
      ROOT_PACKAGE_NAME + ".trace.OtelConventions$1",
      ROOT_PACKAGE_NAME + ".trace.OtelSpan",
      ROOT_PACKAGE_NAME + ".trace.OtelSpan$1",
      ROOT_PACKAGE_NAME + ".trace.OtelSpan$NoopSpan",
      ROOT_PACKAGE_NAME + ".trace.OtelSpan$NoopSpanContext",
      ROOT_PACKAGE_NAME + ".trace.OtelSpanBuilder",
      ROOT_PACKAGE_NAME + ".trace.OtelSpanBuilder$1",
      ROOT_PACKAGE_NAME + ".trace.OtelSpanContext",
      ROOT_PACKAGE_NAME + ".trace.OtelSpanLink",
      ROOT_PACKAGE_NAME + ".trace.OtelSpanLink$1",
      ROOT_PACKAGE_NAME + ".trace.OtelTracer",
      ROOT_PACKAGE_NAME + ".trace.OtelTracerBuilder",
      ROOT_PACKAGE_NAME + ".trace.OtelTracerProvider",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // Context Context.root()
    transformer.applyAdvice(
        isMethod()
            .and(named("root"))
            .and(takesNoArguments())
            .and(returns(named("io.opentelemetry.context.Context"))),
        OpenTelemetryContextInstrumentation.class.getName() + "$ContextRootAdvice");
  }

  public static class ContextRootAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void current(@Advice.Return(readOnly = false) Context result) {
      result = OtelContext.ROOT;
    }
  }
}
