package datadog.trace.instrumentation.mongo;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.bootstrap.instrumentation.java.concurrent.Wrapper;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class AsyncTrampolineInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public AsyncTrampolineInstrumentation() {
    super("mongo", "mongo-reactivestreams");
  }

  @Override
  public String instrumentedType() {
    return "com.mongodb.internal.async.AsyncTrampoline$ContinuationHolder";
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    // The advice only references JDK and bootstrap types; retain this module's 4.x driver boundary.
    return new Reference[] {
      new Reference.Builder("com.mongodb.internal.async.SingleResultCallback").build()
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("enqueue"))
            .and(takesArguments(1))
            .and(takesArgument(0, Runnable.class)),
        AsyncTrampolineInstrumentation.class.getName() + "$EnqueueAdvice");
  }

  public static class EnqueueAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Runnable wrap(
        @Advice.Argument(value = 0, readOnly = false) Runnable continuation) {
      // Deferred callbacks run after the current Mongo callback's context scope has closed.
      continuation = Wrapper.wrap(continuation);
      return continuation;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void cancelOnFailure(
        @Advice.Enter Runnable continuation, @Advice.Thrown Throwable failure) {
      if (failure != null && continuation instanceof Wrapper) {
        ((Wrapper<?>) continuation).cancel();
      }
    }
  }
}
