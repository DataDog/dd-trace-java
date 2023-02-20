package datadog.trace.opentelemetry14;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.opentelemetry14.OtelContextConstants.OTEL_CONTEXT_SPAN_KEY;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.AttachableWrapper;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class OpenTelemetryContextInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.CanShortcutTypeMatching {
  public static final String OTEL_CONTEXT_CLASSNAME = "io.opentelemetry.context.Context";

  public OpenTelemetryContextInstrumentation() {
    super("opentelemetry", "opentelemetry-1");
  }

  @Override
  public String hierarchyMarkerType() {
    return OTEL_CONTEXT_CLASSNAME;
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(OTEL_CONTEXT_CLASSNAME, AgentSpan.class.getName());
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      OTEL_CONTEXT_CLASSNAME, "io.opentelemetry.context.ArrayBasedContext",
    };
  }

  @Override
  public boolean onlyMatchKnownTypes() {
    return isShortcutMatchingEnabled(false);
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".OtelContextConstants", packageName + ".OtelSpan",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    // Context.current()
    transformation.applyAdvice(
        isMethod()
            .and(named("current"))
            .and(takesNoArguments())
            .and(returns(named(OTEL_CONTEXT_CLASSNAME))),
        OpenTelemetryContextInstrumentation.class.getName() + "$ContextCurrentAdvice");
    // Context.get(ContextKey)
    transformation.applyAdvice(
        isMethod()
            .and(named("get"))
            .and(takesArgument(0, named("io.opentelemetry.context.ContextKey"))),
        OpenTelemetryContextInstrumentation.class.getName() + "$ContextGetAdvice");
  }

  public static class ContextCurrentAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void current(@Advice.Return(readOnly = false) Context result) {
      AgentSpan activeSpan = AgentTracer.activeSpan();
      if (activeSpan != null) {
        InstrumentationContext.get(Context.class, AgentSpan.class).putIfAbsent(result, activeSpan);
      }
    }
  }

  public static class ContextGetAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void get(
        @Advice.This Context zis,
        @Advice.Argument(0) ContextKey<?> key,
        @Advice.Return(readOnly = false) Object result) {
      if (!OTEL_CONTEXT_SPAN_KEY.equals(key.toString())) {
        return;
      }
      AgentSpan agentSpan = InstrumentationContext.get(Context.class, AgentSpan.class).get(zis);
      if (agentSpan != null) {
        if (agentSpan instanceof AttachableWrapper) {
          AttachableWrapper attachableSpanWrapper = (AttachableWrapper) agentSpan;
          Object wrapper = attachableSpanWrapper.getWrapper();
          if (wrapper instanceof OtelSpan) {
            result = wrapper;
          } else {
            OtelSpan otelSpan = new OtelSpan(agentSpan);
            attachableSpanWrapper.attachWrapper(otelSpan);
            result = otelSpan;
          }
        }
      }
    }
  }
}
