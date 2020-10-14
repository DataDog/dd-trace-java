package datadog.trace.instrumentation.java.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.implementsInterface;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.cancelTask;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.RunnableFuture;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class RejectedExecutionHandlerInstrumentation extends Instrumenter.Default {

  public RejectedExecutionHandlerInstrumentation() {
    super("java_concurrent", "rejected-execution-handler");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return implementsInterface(named("java.util.concurrent.RejectedExecutionHandler"));
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStore = new HashMap<>(4);
    contextStore.put("java.util.concurrent.RunnableFuture", State.class.getName());
    // TODO get rid of this
    contextStore.put("java.util.concurrent.Runnable", State.class.getName());
    return Collections.unmodifiableMap(contextStore);
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return Collections.singletonMap(
        isMethod()
            .and(named("rejectedExecution"))
            .and(takesArgument(0, named("java.util.concurrent.Runnable"))),
        getClass().getName() + "$Reject");
  }

  public static final class Reject {
    @Advice.OnMethodEnter
    public static void reject(@Advice.Argument(0) Runnable runnable) {
      // not handling rejected work (which will often not manifest in an exception being thrown)
      // leads to unclosed continuations when executors get busy
      // note that this does not handle rejection mechanisms used in Scala, so those need to be
      // handled another way
      if (runnable instanceof RunnableFuture) {
        cancelTask(
            InstrumentationContext.get(RunnableFuture.class, State.class),
            (RunnableFuture) runnable);
      }
      // paranoid about double instrumentation until RunnableInstrumentation is removed
      cancelTask(InstrumentationContext.get(Runnable.class, State.class), runnable);
    }
  }
}
