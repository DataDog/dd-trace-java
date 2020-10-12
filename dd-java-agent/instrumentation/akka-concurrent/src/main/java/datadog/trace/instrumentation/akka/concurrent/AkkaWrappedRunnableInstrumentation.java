package datadog.trace.instrumentation.akka.concurrent;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.endTaskScope;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.startTaskScope;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;

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
 * <p>This instrumentation collaborates with AkkaForkJoinExecutorTaskInstrumentation; its
 * responsibility is to activate any captured scope.
 */
@AutoService(Instrumenter.class)
public final class AkkaWrappedRunnableInstrumentation extends Instrumenter.Default {

  public AkkaWrappedRunnableInstrumentation() {
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
    return singletonMap(isMethod().and(named("run")), getClass().getName() + "$Run");
  }

  public static final class Run {
    @Advice.OnMethodEnter
    public static TraceScope before(@Advice.Argument(0) Runnable wrapped) {
      return startTaskScope(InstrumentationContext.get(Runnable.class, State.class), wrapped);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void after(@Advice.Enter TraceScope scope) {
      endTaskScope(scope);
    }
  }
}
