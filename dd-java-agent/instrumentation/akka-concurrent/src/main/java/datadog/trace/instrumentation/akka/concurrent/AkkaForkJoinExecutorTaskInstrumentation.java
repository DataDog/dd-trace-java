package datadog.trace.instrumentation.akka.concurrent;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import datadog.trace.context.TraceScope;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * akka.dispatch.ForkJoinExecutorConfigurator$AkkaForkJoinTask requires special treatment and can't
 * be handled generically despite being a subclass of akka.dispatch.ForkJoinTask, because of its
 * error handling.
 *
 * <p>This instrumentation collaborates with AkkaWrappedRunnableInstrumentation; its responsibility
 * is to capture context if there is an active scope.
 */
@AutoService(Instrumenter.class)
public final class AkkaForkJoinExecutorTaskInstrumentation extends Instrumenter.Default {
  public AkkaForkJoinExecutorTaskInstrumentation() {
    super("java_concurrent", "akka_concurrent");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // prevents Runnable from being instrumented unless this
    // instrumentation would take effect (unless something else
    // instruments it).
    return hasClassesNamed("akka.dispatch.ForkJoinExecutorConfigurator$AkkaForkJoinTask");
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(Runnable.class.getName(), State.class.getName());
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("akka.dispatch.ForkJoinExecutorConfigurator$AkkaForkJoinTask");
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        isConstructor().and(takesArgument(0, named(Runnable.class.getName()))),
        getClass().getName() + "$Construct");
  }

  public static final class Construct {
    @Advice.OnMethodExit
    public static void construct(@Advice.Argument(0) Runnable wrapped) {
      TraceScope activeScope = activeScope();
      if (null != activeScope) {
        InstrumentationContext.get(Runnable.class, State.class)
            .putIfAbsent(wrapped, State.FACTORY)
            .captureAndSetContinuation(activeScope);
      }
    }
  }
}
