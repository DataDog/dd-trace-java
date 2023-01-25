package datadog.trace.opentelemetry1;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.BaggageEntry;
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
      //      packageName + ".OtelBaggage",
      //      packageName + ".OtelSpanBuilder",
      //      packageName + ".OtelSpanContext",
      //      packageName + ".OtelTracer",
      //      packageName + ".OtelTracerBuilder",
      //      packageName + ".OtelTracerProvider",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("current"))
            .and(takesNoArguments())
            .and(returns(named(OTEL_CONTEXT_CLASSNAME))),
        OpenTelemetryContextInstrumentation.class.getName() + "$ContextCurrentAdvice");
    transformation.applyAdvice(
        isMethod()
            .and(named("get"))
            .and(takesArgument(0, named("io.opentelemetry.context.ContextKey"))),
        OpenTelemetryContextInstrumentation.class.getName() + "$ContextGetAdvice");
    transformation.applyAdvice(
        isMethod()
            .and(named("with"))
            .and(takesArguments(2))
            .and(takesArgument(0, named("io.opentelemetry.context.ContextKey"))),
        OpenTelemetryContextInstrumentation.class.getName() + "$ContextWithAdvice");
  }

  public static class ContextCurrentAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void current(@Advice.Return(readOnly = false) Context result) {
      AgentSpan activeSpan = AgentTracer.activeSpan();
      if (activeSpan != null) {
        System.out.println(">>> [Context.current()] Injecting active span");
        InstrumentationContext.get(Context.class, AgentSpan.class).putIfAbsent(result, activeSpan);
      } else {
        System.out.println(">>> [Context.current()] No active span to inject");
      }
    }
  }

  public static class ContextGetAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void get(
        @Advice.This Context zis,
        @Advice.Argument(0) ContextKey<?> key,
        @Advice.Return(readOnly = false) Object result) {
      if (!"opentelemetry-baggage-key".equals(key.toString())) {
        System.out.println(">>> [Context.get()] Skipping key " + key);
        return;
      }
      AgentSpan agentSpan = InstrumentationContext.get(Context.class, AgentSpan.class).get(zis);
      if (agentSpan != null) {
        System.out.println(">>> [Context.get()] Creating OtelBaggage");
        // TODO Can I use the provided ImmutableContext instead?
        result = OtelBaggage.fromContext(agentSpan.context());
      } else {
        System.out.println(">>> [Context.get()] No AgentSpan injected into OTel Context");
      }
    }
  }

  public static class ContextWithAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void with(
        @Advice.This Context zis,
        @Advice.Argument(0) ContextKey<?> key,
        @Advice.Argument(1) Object value) {
      if (!"opentelemetry-baggage-key".equals(key.toString())) {
        System.out.println(">>> [Context.with()] Skipping key " + key);
        return;
      }
      if (!(value instanceof Baggage)) {
        System.out.println(
            ">>> [Context.with()] Result not a Baggage: "
                + (value == null ? "null value" : value.getClass().getName()));
        return;
      }
      Baggage baggage = (Baggage) value;
      AgentSpan agentSpan = InstrumentationContext.get(Context.class, AgentSpan.class).get(zis);
      if (agentSpan != null) {
        System.out.println(">>> [Context.with()] Storing baggage into span");
        // TODO Need a way to remove baggage item from DD span that are not present in OTel baggage
        for (Map.Entry<String, BaggageEntry> mapEntry : baggage.asMap().entrySet()) {
          agentSpan.setBaggageItem(mapEntry.getKey(), mapEntry.getValue().getValue());
        }
      } else {
        System.out.println(">>> [Context.with()] No AgentSpan injected into OTel Context");
      }
    }
  }
}
