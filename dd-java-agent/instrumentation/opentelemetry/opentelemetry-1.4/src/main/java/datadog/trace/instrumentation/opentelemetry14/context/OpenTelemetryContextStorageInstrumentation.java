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
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.AttachableWrapper;
import datadog.trace.instrumentation.opentelemetry14.trace.OtelSpan;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class OpenTelemetryContextStorageInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.CanShortcutTypeMatching {

  public OpenTelemetryContextStorageInstrumentation() {
    super("opentelemetry.experimental", "opentelemetry-1");
  }

  @Override
  protected boolean defaultEnabled() {
    return InstrumenterConfig.get().isTraceOtelEnabled();
  }

  @Override
  public String hierarchyMarkerType() {
    return "io.opentelemetry.context.ContextStorage";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "io.opentelemetry.context.ThreadLocalContextStorage",
      "io.opentelemetry.context.StrictContextStorage",
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
    // Context ContextStorage.current()
    transformer.applyAdvice(
        isMethod()
            .and(named("current"))
            .and(takesNoArguments())
            .and(returns(named("io.opentelemetry.context.Context"))),
        OpenTelemetryContextStorageInstrumentation.class.getName()
            + "$ContextStorageCurrentAdvice");
  }

  public static class ContextStorageCurrentAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void current(@Advice.Return(readOnly = false) Context result) {
      // Check empty context
      AgentSpan agentCurrentSpan = AgentTracer.activeSpan();
      if (null == agentCurrentSpan) {
        result = OtelContext.ROOT;
        return;
      }
      // Get OTel current span
      Span otelCurrentSpan = null;
      if (agentCurrentSpan instanceof AttachableWrapper) {
        Object wrapper = ((AttachableWrapper) agentCurrentSpan).getWrapper();
        if (wrapper instanceof OtelSpan) {
          otelCurrentSpan = (OtelSpan) wrapper;
        }
      }
      if (otelCurrentSpan == null) {
        otelCurrentSpan = new OtelSpan(agentCurrentSpan);
      }
      // Get OTel root span
      Span otelRootSpan = null;
      AgentSpan agentRootSpan = agentCurrentSpan.getLocalRootSpan();
      if (agentRootSpan instanceof AttachableWrapper) {
        Object wrapper = ((AttachableWrapper) agentRootSpan).getWrapper();
        if (wrapper instanceof OtelSpan) {
          otelRootSpan = (OtelSpan) wrapper;
        }
      }
      if (otelRootSpan == null) {
        otelRootSpan = new OtelSpan(agentRootSpan);
      }
      result = new OtelContext(otelCurrentSpan, otelRootSpan);
    }
  }
}
