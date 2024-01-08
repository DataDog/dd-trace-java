package datadog.trace.instrumentation.slick;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/** Instruments runnables from the slick framework, which are excluded elsewhere. */
@AutoService(Instrumenter.class)
public final class SlickRunnableInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {
  public SlickRunnableInstrumentation() {
    super("slick");
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(Runnable.class.getName(), State.class.getName());
  }

  @Override
  public String hierarchyMarkerType() {
    return "slick.util.AsyncExecutor"; // implies existence of the various slick-runnables
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return nameStartsWith("slick.").and(implementsInterface(named(Runnable.class.getName())));
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(isConstructor(), getClass().getName() + "$Construct");
    transformation.applyAdvice(named("run").and(takesNoArguments()), getClass().getName() + "$Run");
  }

  public static final class Construct {
    @Advice.OnMethodExit
    public static void capture(@Advice.This Runnable zis) {
      AdviceUtils.capture(InstrumentationContext.get(Runnable.class, State.class), zis, true);
    }
  }

  public static final class Run {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope enter(@Advice.This final Runnable zis) {
      final ContextStore<Runnable, State> contextStore =
          InstrumentationContext.get(Runnable.class, State.class);
      return AdviceUtils.startTaskScope(contextStore, zis);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Enter final AgentScope scope) {
      AdviceUtils.endTaskScope(scope);
    }
  }
}
