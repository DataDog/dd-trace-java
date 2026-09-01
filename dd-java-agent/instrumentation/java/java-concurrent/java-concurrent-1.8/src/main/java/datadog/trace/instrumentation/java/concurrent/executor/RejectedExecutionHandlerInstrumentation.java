package datadog.trace.instrumentation.java.concurrent.executor;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameEndsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.instrumentation.java.concurrent.ConcurrentInstrumentationNames.EXECUTOR_INSTRUMENTATION_NAME;
import static java.util.Arrays.asList;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import datadog.context.ContextContinuation;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import datadog.trace.bootstrap.instrumentation.java.concurrent.TPEHelper;
import datadog.trace.bootstrap.instrumentation.java.concurrent.Wrapper;
import datadog.trace.bootstrap.instrumentation.jfr.backpressure.BackpressureProfiling;
import java.util.concurrent.RunnableFuture;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public class RejectedExecutionHandlerInstrumentation
    implements Instrumenter.ForBootstrap,
        Instrumenter.CanShortcutTypeMatching,
        Instrumenter.HasMethodAdvice {
  @Override
  public boolean onlyMatchKnownTypes() {
    return InstrumenterConfig.get()
        .isIntegrationShortcutMatchingEnabled(
            asList(EXECUTOR_INSTRUMENTATION_NAME, "rejected-execution-handler"), false);
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "java.util.concurrent.ThreadPoolExecutor$AbortPolicy",
      "java.util.concurrent.ThreadPoolExecutor$DiscardPolicy",
      "java.util.concurrent.ThreadPoolExecutor$DiscardOldestPolicy",
      "java.util.concurrent.ThreadPoolExecutor$CallerRunsPolicy"
    };
  }

  @Override
  public String hierarchyMarkerType() {
    return null; // bootstrap type
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(
        named("java.util.concurrent.RejectedExecutionHandler")
            .or(nameEndsWith("netty.util.concurrent.RejectedExecutionHandler")));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
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
    public static TPEHelper.RejectedTask handle(
        @Advice.This Object zis, @Advice.Argument(readOnly = false, value = 0) Runnable runnable) {
      Wrapper<?> wrapper = null;
      ContextContinuation continuation = null;
      if (runnable instanceof Wrapper) {
        wrapper = (Wrapper<?>) runnable;
        runnable = wrapper.unwrap();
      } else {
        continuation =
            TPEHelper.prepareRejectedTask(
                InstrumentationContext.get(Runnable.class, State.class), runnable);
      }
      if (Config.get().isProfilingBackPressureSamplingEnabled()) {
        // record this event before the handler executes, which will help
        // explain why the task is running on the submitter thread for
        // rejection policies which run on the caller (CallerRunsPolicy or user-provided)
        BackpressureProfiling.getInstance().process(zis.getClass(), runnable);
      }
      return wrapper == null && continuation == null
          ? null
          : new TPEHelper.RejectedTask(wrapper, continuation);
    }

    // must execute after in case the handler actually runs the runnable,
    // which is preferable to cancelling the continuation
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void reject(
        @Advice.Enter TPEHelper.RejectedTask rejected,
        @Advice.Argument(value = 0) Runnable runnable) {
      // not handling rejected work (which will often not manifest in an exception being thrown)
      // leads to unclosed continuations when executors get busy
      // note that this does not handle rejection mechanisms used in Scala, so those need to be
      // handled another way
      if (null != rejected) {
        rejected.close();
      } else {
        if (runnable instanceof RunnableFuture) {
          datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.cancelTask(
              InstrumentationContext.get(RunnableFuture.class, State.class),
              (RunnableFuture<?>) runnable);
        }
      }
    }
  }
}
