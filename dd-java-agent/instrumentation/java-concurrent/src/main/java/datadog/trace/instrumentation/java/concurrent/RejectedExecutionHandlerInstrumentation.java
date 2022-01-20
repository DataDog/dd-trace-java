package datadog.trace.instrumentation.java.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.hasInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameEndsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.cancelTask;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import datadog.trace.bootstrap.instrumentation.java.concurrent.Wrapper;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.RunnableFuture;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class RejectedExecutionHandlerInstrumentation extends Instrumenter.Tracing {

  public RejectedExecutionHandlerInstrumentation() {
    super("java_concurrent", "rejected-execution-handler");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return NameMatchers.<TypeDescription>namedOneOf(
            "java.util.concurrent.ThreadPoolExecutor$AbortPolicy",
            "java.util.concurrent.ThreadPoolExecutor$DiscardPolicy",
            "java.util.concurrent.ThreadPoolExecutor$DiscardOldestPolicy",
            "java.util.concurrent.ThreadPoolExecutor$CallerRunsPolicy")
        .or(
            hasInterface(
                named("java.util.concurrent.RejectedExecutionHandler")
                    .or(nameEndsWith("netty.util.concurrent.RejectedExecutionHandler"))));
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStore = new HashMap<>(4);
    contextStore.put("java.util.concurrent.RunnableFuture", State.class.getName());
    // TODO get rid of this
    contextStore.put("java.lang.Runnable", State.class.getName());
    return Collections.unmodifiableMap(contextStore);
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            // JDK or netty
            .and(namedOneOf("rejectedExecution", "rejected"))
            // must not constrain or use second parameter
            .and(takesArgument(0, named("java.lang.Runnable"))),
        getClass().getName() + "$Reject");
  }

  public static final class Reject {
    // remove our wrapper before calling the handler (save wrapper, so we can cancel it later)
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Wrapper<?> handle(
        @Advice.Argument(readOnly = false, value = 0) Runnable runnable) {
      if (runnable instanceof Wrapper) {
        Wrapper<?> wrapper = (Wrapper<?>) runnable;
        runnable = wrapper.unwrap();
        return wrapper;
      }
      return null;
    }

    // must execute after in case the handler actually runs the runnable,
    // which is preferable to cancelling the continuation
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void reject(
        @Advice.Enter Wrapper<?> wrapper, @Advice.Argument(value = 0) Runnable runnable) {
      // not handling rejected work (which will often not manifest in an exception being thrown)
      // leads to unclosed continuations when executors get busy
      // note that this does not handle rejection mechanisms used in Scala, so those need to be
      // handled another way
      if (null != wrapper) {
        wrapper.cancel();
      } else {
        if (runnable instanceof RunnableFuture) {
          cancelTask(
              InstrumentationContext.get(RunnableFuture.class, State.class),
              (RunnableFuture<?>) runnable);
        }
        // paranoid about double instrumentation until RunnableInstrumentation is removed
        cancelTask(InstrumentationContext.get(Runnable.class, State.class), runnable);
      }
    }
  }
}
