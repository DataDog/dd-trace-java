package datadog.trace.instrumentation.scala.concurrent;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.declaresMethod;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import datadog.trace.context.TraceScope;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import scala.concurrent.forkjoin.ForkJoinTask;

/**
 * Instrument {@link ForkJoinTask}.
 *
 * <p>Note: There are quite a few separate implementations of {@code ForkJoinTask}/{@code
 * ForkJoinPool}: JVM, Akka, Scala, Netty to name a few. This class handles Scala version.
 */
@AutoService(Instrumenter.class)
public final class ScalaForkJoinTaskInstrumentation extends Instrumenter.Tracing {

  public ScalaForkJoinTaskInstrumentation() {
    super("java_concurrent", "scala_concurrent");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    return hasClassesNamed("scala.concurrent.forkjoin.ForkJoinTask");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    // this type is constructed on entry to the JFP, and can be used to track
    // the lifecycle of tasks
    return declaresMethod(namedOneOf("exec", "fork", "cancel"))
        .and(extendsClass(named("scala.concurrent.forkjoin.ForkJoinTask")));
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("scala.concurrent.forkjoin.ForkJoinTask", State.class.getName());
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(isMethod().and(named("exec")), getClass().getName() + "$Exec");
    transformation.applyAdvice(isMethod().and(named("fork")), getClass().getName() + "$Fork");
    transformation.applyAdvice(isMethod().and(named("cancel")), getClass().getName() + "$Cancel");
  }

  public static final class Exec {
    @Advice.OnMethodEnter
    public static <T> TraceScope before(@Advice.This ForkJoinTask<T> task) {
      State state = InstrumentationContext.get(ForkJoinTask.class, State.class).get(task);
      if (null != state) {
        TraceScope.Continuation continuation = state.getAndResetContinuation();
        if (null != continuation) {
          return continuation.activate();
        }
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void after(@Advice.Enter TraceScope scope) {
      if (null != scope) {
        scope.close();
      }
    }
  }

  public static final class Fork {
    @Advice.OnMethodEnter
    public static <T> void fork(@Advice.This ForkJoinTask<T> task) {
      TraceScope activeScope = activeScope();
      if (null != activeScope) {
        InstrumentationContext.get(ForkJoinTask.class, State.class)
            .putIfAbsent(task, State.FACTORY)
            .captureAndSetContinuation(activeScope);
      }
    }
  }

  public static final class Cancel {
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static <T> void cancel(@Advice.This ForkJoinTask<T> task) {
      State state = InstrumentationContext.get(ForkJoinTask.class, State.class).get(task);
      if (null != state) {
        state.closeContinuation();
      }
    }
  }
}
