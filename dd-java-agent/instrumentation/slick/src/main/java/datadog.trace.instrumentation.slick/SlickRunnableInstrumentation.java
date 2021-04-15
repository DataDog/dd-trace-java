package datadog.trace.instrumentation.slick;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.hasInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import datadog.trace.context.TraceScope;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/** Instruments runnables from the slick framework, which are excluded elsewhere. */
@AutoService(Instrumenter.class)
public final class SlickRunnableInstrumentation extends Instrumenter.Tracing {
  public SlickRunnableInstrumentation() {
    super("slick");
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(Runnable.class.getName(), State.class.getName());
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return NameMatchers.<TypeDescription>nameStartsWith("slick.")
        .and(hasInterface(named(Runnable.class.getName())));
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    Map<ElementMatcher<? super MethodDescription>, String> transformers = new HashMap<>(4);
    transformers.put(isConstructor(), getClass().getName() + "$Construct");
    transformers.put(named("run").and(takesNoArguments()), getClass().getName() + "$Run");
    return transformers;
  }

  public static final class Construct {
    @Advice.OnMethodExit
    public static void capture(@Advice.This Runnable zis) {
      TraceScope activeScope = activeScope();
      if (null != activeScope) {
        InstrumentationContext.get(Runnable.class, State.class)
            .putIfAbsent(zis, State.FACTORY)
            .captureAndSetContinuation(activeScope);
      }
    }
  }

  public static final class Run {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static TraceScope enter(@Advice.This final Runnable zis) {
      final ContextStore<Runnable, State> contextStore =
          InstrumentationContext.get(Runnable.class, State.class);
      return AdviceUtils.startTaskScope(contextStore, zis);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Enter final TraceScope scope) {
      AdviceUtils.endTaskScope(scope);
    }
  }
}
