package java.util.concurrent;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static java.util.concurrent.CompletableFuture.ASYNC;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ConcurrentState;
import java.util.concurrent.CompletableFuture.UniCompletion;
import net.bytebuddy.asm.Advice;

// This class is put into java.util.concurrent to allow access to package private classes.
public final class CompletableFutureAdvice {

  public static final class UniConstructor {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterInit(@Advice.This UniCompletion zis) {
      AgentSpan span = activeSpan();
      if (zis.isLive() && span != null) {
        ContextStore<UniCompletion, ConcurrentState> contextStore =
            InstrumentationContext.get(UniCompletion.class, ConcurrentState.class);
        ConcurrentState.captureContinuation(contextStore, zis, span);
      }
    }

    private static void muzzleCheck(final UniCompletion callback) {
      callback.exec();
    }
  }

  public static final class UniSubTryFire {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope enter(
        @Advice.This UniCompletion zis,
        @Advice.Local("hadExecutor") boolean hadExecutor,
        @Advice.Local("wasClaimed") boolean wasClaimed,
        @Advice.Local("wasLive") boolean wasLive) {
      hadExecutor = zis.executor != null;
      wasClaimed = zis.getForkJoinTaskTag() == 1;
      wasLive = zis.isLive();
      // If this UniCompletion is not live, then we don't need to activate a span
      if (!wasLive) {
        return null;
      }
      ContextStore<UniCompletion, ConcurrentState> contextStore =
          InstrumentationContext.get(UniCompletion.class, ConcurrentState.class);
      return ConcurrentState.activateAndContinueContinuation(contextStore, zis);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(
        @Advice.Enter AgentScope scope,
        @Advice.Thrown final Throwable throwable,
        @Advice.This UniCompletion zis,
        @Advice.Argument(0) int mode,
        @Advice.Local("hadExecutor") boolean hadExecutor,
        @Advice.Local("wasClaimed") boolean wasClaimed,
        @Advice.Local("wasLive") boolean wasLive) {
      // If it wasn't live when we entered, then do nothing
      if (!wasLive) {
        return;
      }
      // Try to clean up if the CompletableFuture has been completed.
      //
      // 1) If the mode is ASYNC, it means that someone else has claimed the
      // task for us, and we have run it to completion.
      // 2) We have claimed the task and we will not mark it as finished since we will
      // hand off to a `UniRelay` which can happen in `uniCompose`.
      // 3) If `isLive` is false, then either we or someone else just completed the task.
      ContextStore<UniCompletion, ConcurrentState> contextStore = null;
      boolean claimed = !wasClaimed && !hadExecutor && zis.getForkJoinTaskTag() == 1;
      if (mode == ASYNC || (mode < ASYNC && claimed) || !zis.isLive()) {
        contextStore = InstrumentationContext.get(UniCompletion.class, ConcurrentState.class);
        ConcurrentState.cancelAndClearContinuation(contextStore, zis);
      }
      if (scope != null || throwable != null) {
        if (contextStore == null) {
          contextStore = InstrumentationContext.get(UniCompletion.class, ConcurrentState.class);
        }
        ConcurrentState.closeScope(contextStore, zis, scope, throwable);
      }
    }

    private static void muzzleCheck(final UniCompletion callback) {
      callback.exec();
    }
  }
}
