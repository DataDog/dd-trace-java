package datadog.trace.instrumentation.java.concurrent;

import static datadog.trace.agent.tooling.ByteBuddyElementMatchers.safeHasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import akka.actor.Scheduler;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import datadog.trace.context.TraceScope;
import io.opentracing.Scope;
import io.opentracing.util.GlobalTracer;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import scala.concurrent.ExecutionContext;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

//@Slf4j
////@AutoService(Instrumenter.class)
//public final class ExecutionContextInstrumentation extends Instrumenter.Default {
//
//  public ExecutionContextInstrumentation() {
//    super(AbstractExecutorInstrumentation.EXEC_NAME);
//  }
//
//  @Override
//  public ElementMatcher<TypeDescription> typeMatcher() {
////    return named("akka.actor.LightArrayRevolverScheduler");
//    return not(isInterface())
//        .and(safeHasSuperType(named(ExecutionContext.class.getName())));
//  }
//
//  @Override
//  public String[] helperClassNames() {
//    return new String[] {
//      AbstractExecutorInstrumentation.class.getPackage().getName() + ".ExecutorInstrumentationUtils"
//    };
//  }
//
//  @Override
//  public Map<String, String> contextStore() {
//    final Map<String, String> map = new HashMap<>();
//    map.put(Runnable.class.getName(), State.class.getName());
//    return Collections.unmodifiableMap(map);
//  }
//
//  @Override
//  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
////    System.out.println("JJJJJJJJJJJJJJJJJJJJJJJJJJJJJJJJJJJJJJJ");
//    final Map<ElementMatcher<? super MethodDescription>, String> transformers = new HashMap<>();
////    transformers.put(
////      named("scheduleOnce")
////        .and(takesArgument(1, named(Runnable.class.getName()))),
////      RunnableTaskStateAdvice.class.getName());
//    transformers.put(
//      named("execute")
//        .and(takesArgument(0, named(Runnable.class.getName()))),
//      RunnableTaskStateAdvice.class.getName());
//    return transformers;
//  }
//
//
//  public static class RunnableTaskStateAdvice {
//
//    @Advice.OnMethodEnter(suppress = Throwable.class)
//    public static State enterJobSubmit(
//      @Advice.Argument(value = 0, readOnly = false) final Runnable task) {
////      @Advice.Argument(value = 1, readOnly = false) final Runnable task) {
////      System.out.println("SUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUU");
//      final Scope scope = GlobalTracer.get().scopeManager().active();
//      if (scope != null) {
//        final ContextStore<Runnable, State> contextStore =
//          InstrumentationContext.get(Runnable.class, State.class);
//        return ExecutorInstrumentationUtils.setupState(contextStore, task, (TraceScope) scope);
//      }
//      return null;
////      if (ExecutorInstrumentationUtils.shouldAttachStateToTask(task, executor)) {
////      }
//    }
//
//    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
//    public static void exitJobSubmit(
//      @Advice.Enter final State state,
//      @Advice.Thrown final Throwable throwable) {
//      if (null != state && null != throwable) {
//        // state.closeContinuation();
//      }
//    }
//  }
//}
