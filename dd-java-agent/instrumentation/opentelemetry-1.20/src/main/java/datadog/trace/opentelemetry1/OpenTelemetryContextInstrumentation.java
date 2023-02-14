package datadog.trace.opentelemetry1;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.core.scopemanager.ScopeContext.BAGGAGE_KEY;
import static datadog.trace.opentelemetry1.OtelContextConstants.OTEL_CONTEXT_BAGGAGE_KEY;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentScopeContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.core.DDBaggage;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.BaggageEntry;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

// TODO Supporting Context.makeCurrent() requires to change the internal ScopeManager

@AutoService(Instrumenter.class)
public class OpenTelemetryContextInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.CanShortcutTypeMatching {

  public static final String OTEL_CONTEXT_CLASSNAME = "io.opentelemetry.context.Context";
  public static final String OTEL_SCOPE_CLASSNAME = "io.opentelemetry.context.Scope";

  public OpenTelemetryContextInstrumentation() {
    super("opentelemetry", "opentelemetry-1");
  }

  @Override
  public String hierarchyMarkerType() {
    return OTEL_CONTEXT_CLASSNAME;
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(OTEL_CONTEXT_CLASSNAME, AgentScopeContext.class.getName());
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
      "datadog.trace.core.DDBaggage",
      packageName + ".OtelBaggage",
      packageName + ".OtelContextConstants",
      packageName + ".OtelScope"
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
    // Context.with(ContextKey, V)
    transformation.applyAdvice(
        isMethod()
            .and(named("with"))
            .and(takesArguments(2))
            .and(takesArgument(0, named("io.opentelemetry.context.ContextKey"))),
        OpenTelemetryContextInstrumentation.class.getName() + "$ContextWithAdvice");
    // Context.makeCurrent()
    transformation.applyAdvice(
        isMethod()
            .and(named("makeCurrent"))
            .and(takesNoArguments())
            .and(returns(named(OTEL_SCOPE_CLASSNAME))),
        OpenTelemetryContextInstrumentation.class.getName() + "$ContextMakeCurrentAdvice");
  }

  public static class ContextCurrentAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void current(@Advice.Return(readOnly = false) Context result) {
      AgentScope activeScope = AgentTracer.activeScope();
      if (activeScope != null) {
        System.out.println(">>> [Context.current()] Injecting active scope context");
        InstrumentationContext.get(Context.class, AgentScopeContext.class)
            .putIfAbsent(result, activeScope.context());
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
      if (!OTEL_CONTEXT_BAGGAGE_KEY.equals(key.toString())) {
        System.out.println(">>> [Context.get()] Skipping key " + key);
        return;
      }
      AgentScopeContext agentScopeContext =
          InstrumentationContext.get(Context.class, AgentScopeContext.class).get(zis);
      if (agentScopeContext != null) {
        System.out.println(">>> [Context.get()] Creating baggage from context");
        result = OtelBaggage.fromDdBaggage(agentScopeContext.baggage());
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
        @Advice.Argument(1) Object value,
        @Advice.Return(readOnly = false) Context result) {
      if (!OTEL_CONTEXT_BAGGAGE_KEY.equals(key.toString())) {
        System.out.println(">>> [Context.with()] Skipping key " + key);
        return;
      }
      if (!(value instanceof Baggage)) {
        String valueType = value == null ? "null value" : value.getClass().getName();
        System.out.println(">>> [Context.with()] Result not a Baggage: " + valueType);
        return;
      }
      Baggage baggage = (Baggage) value;
      AgentScopeContext agentScopeContext =
          InstrumentationContext.get(Context.class, AgentScopeContext.class).get(zis);
      if (agentScopeContext != null) {
        System.out.println(">>> [Context.with()] Storing baggage into span");
        datadog.trace.bootstrap.instrumentation.api.Baggage.BaggageBuilder builder =
            DDBaggage.builder();
        for (Map.Entry<String, BaggageEntry> entry : baggage.asMap().entrySet()) {
          builder.put(entry.getKey(), entry.getValue().getValue());
        }
        agentScopeContext = agentScopeContext.with(BAGGAGE_KEY, builder.build());
        InstrumentationContext.get(Context.class, AgentScopeContext.class)
            .put(result, agentScopeContext);
      } else {
        System.out.println(">>> [Context.with()] No AgentScopeContext injected into OTel Context");
      }
    }
  }

  public static class ContextMakeCurrentAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void makeCurrent(
        @Advice.This Context zis, @Advice.Return(readOnly = false) Scope result) {
      AgentScopeContext agentScopeContext =
          InstrumentationContext.get(Context.class, AgentScopeContext.class).get(zis);
      if (agentScopeContext != null) {
        System.out.println(">>> [Context.makeCurrent()] Activate scope context");
        AgentScope agentScope = AgentTracer.get().activateContext(agentScopeContext);
        result = new OtelScope(result, agentScope);
      } else {
        System.out.println(">>> [Context.makeCurrent()] No scope context attached to OTel Context");
      }
    }
  }
}
