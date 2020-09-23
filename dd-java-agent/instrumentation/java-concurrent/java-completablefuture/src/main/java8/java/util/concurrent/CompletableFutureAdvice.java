package java.util.concurrent;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static java.util.concurrent.CompletableFuture.ASYNC;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ConcurrentState;
import datadog.trace.context.TraceScope;
import datadog.trace.instrumentation.java.completablefuture.UniCompletionHelper;
import java.util.concurrent.CompletableFuture.UniCompletion;
import net.bytebuddy.asm.Advice;

// This class is put into java.util.concurrent to allow access to package private classes.
public final class CompletableFutureAdvice {

  public static final class UniConstructor {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterInit(@Advice.This UniCompletion thiz) {
      TraceScope scope = activeScope();
      if (thiz.isLive() && scope != null) {
        ContextStore<UniCompletion, ConcurrentState> contextStore =
            InstrumentationContext.get(UniCompletion.class, ConcurrentState.class);
        ConcurrentState.captureScope(contextStore, thiz, scope);
      }
    }

    private static void muzzleCheck(final UniCompletion callback) {
      callback.exec();
    }
  }

  public static final class UniClaim {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterClaim(@Advice.Return boolean claim) {
      UniCompletionHelper.setClaim(claim);
    }

    private static void muzzleCheck(final UniCompletion callback) {
      callback.exec();
    }
  }

  public static final class UniSubTryFire {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static TraceScope enter(
        @Advice.This UniCompletion thiz,
        @Advice.Local("hadExecutor") boolean hadExecutor,
        @Advice.Local("wasClaimed") boolean wasClaimed,
        @Advice.Local("wasLive") boolean wasLive) {
      // TODO remove the unicompletionhelper thing or make it into a stack?
      //  And check all the `claim` calls to see wif we can rely on it
      hadExecutor = thiz.executor != null;
      wasClaimed = thiz.getForkJoinTaskTag() == 1; // = UniCompletionHelper.getAndResetClaim();
      wasLive = thiz.isLive();
      // If this UniCompletion is not live, then we don't need to activate a span
      if (!wasLive) {
        return null;
      }
      ContextStore<UniCompletion, ConcurrentState> contextStore =
          InstrumentationContext.get(UniCompletion.class, ConcurrentState.class);
      return ConcurrentState.activateAndContinueContinuation(contextStore, thiz);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(
        @Advice.Enter TraceScope scope,
        @Advice.Thrown final Throwable throwable,
        @Advice.This UniCompletion thiz,
        @Advice.Argument(0) int mode,
        @Advice.Local("hadExecutor") boolean hadExecutor,
        @Advice.Local("wasClaimed") boolean wasClaimed,
        @Advice.Local("wasLive") boolean wasLive) {
      // boolean claimed = UniCompletionHelper.getAndRestoreClaim(wasClaimed);

      // If it wasn't live when we entered, then do nothing
      if (!wasLive) {
        return;
      }
      // Try to clean up if the CompletableFuture has been completed.
      //
      // 1) If the mode is ASYNC, it means that someone else has claimed the
      // task for us, and we have run it to completion.
      // 2) If `isLive` is false, then either we or someone else just completed the task.
      // 3) We have claimed the task and we will not mark it as finished since we will
      // hand off to a `UniRelay` which can happen in `uniCompose`.
      ContextStore<UniCompletion, ConcurrentState> contextStore =
          InstrumentationContext.get(UniCompletion.class, ConcurrentState.class);
      boolean claimed = !wasClaimed && !hadExecutor && thiz.getForkJoinTaskTag() == 1;
      if (mode == ASYNC || (mode < ASYNC && claimed) || !thiz.isLive()) {
        ConcurrentState.closeAndClearContinuation(contextStore, thiz);
      }
      ConcurrentState.closeScope(contextStore, thiz, scope, throwable);
    }

    private static void muzzleCheck(final UniCompletion callback) {
      callback.exec();
    }
  }
}
