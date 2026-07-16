// Copyright 2026 Datadog, Inc.
package datadog.trace.bootstrap.instrumentation.java.concurrent;

import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;

/** Helper for synchronously bracketing untraced {@code Thread.sleep} intervals. */
public final class TaskBlockHelper {
  private TaskBlockHelper() {}

  /** State required to balance a native TaskBlock interval accepted at sleep entry. */
  public static final class State {
    final ProfilingContextIntegration profiling;
    final long token;

    State(ProfilingContextIntegration profiling, long token) {
      this.profiling = profiling;
      this.token = token;
    }
  }

  /** Starts a synchronous sleeping interval through the currently installed integration. */
  public static State captureForSleep() {
    try {
      return captureForSleep(AgentTracer.get().getProfilingContext());
    } catch (Throwable ignored) {
      return null;
    }
  }

  static State captureForSleep(ProfilingContextIntegration profiling) {
    try {
      if (profiling == null) {
        return null;
      }
      long token = profiling.beginTaskBlock(ProfilingContextIntegration.BLOCKING_STATE_SLEEPING);
      return token == 0L ? null : new State(profiling, token);
    } catch (Throwable ignored) {
      return null;
    }
  }

  /** Completes a sleeping interval accepted by {@link #captureForSleep()}. */
  public static void finish(State state) {
    if (state == null) {
      return;
    }
    try {
      state.profiling.endTaskBlock(state.token, 0L, 0L);
    } catch (Throwable ignored) {
    }
  }
}
