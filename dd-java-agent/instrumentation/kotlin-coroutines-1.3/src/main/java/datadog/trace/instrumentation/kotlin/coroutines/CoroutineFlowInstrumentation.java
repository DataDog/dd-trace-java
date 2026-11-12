package datadog.trace.instrumentation.kotlin.coroutines;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.agent.tooling.Instrumenter;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import net.bytebuddy.asm.Advice;

/** Preserves the active span across flowOn's undispatched upstream and downstream context swaps. */
public class CoroutineFlowInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  @Override
  public String instrumentedType() {
    return "kotlinx.coroutines.flow.internal.ChannelFlowOperator";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("collectWithContextUndispatched")
            .and(takesArguments(3))
            .and(takesArgument(1, named("kotlin.coroutines.CoroutineContext"))),
        getClass().getName() + "$CollectAdvice");
  }

  public static class CollectAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(
        @Advice.Argument(value = 1, readOnly = false) CoroutineContext upstream,
        @Advice.Argument(2) Continuation<?> continuation) {
      upstream = DatadogThreadContextElement.forFlowCollection(upstream, continuation.getContext());
    }
  }
}
