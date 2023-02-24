package datadog.trace.instrumentation.grpc;

import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;

/**
 * Runnable which records how long it waited to execute. Don't use this except when wrapping can be
 * shown to be safe TODO: generalise this behaviour in the scope manager, then delete this class.
 */
public final class TimedRunnable implements Runnable {

  private final long creationTime;
  private final Runnable delegate;

  public TimedRunnable(Runnable delegate) {
    this.delegate = delegate;
    this.creationTime = System.currentTimeMillis();
  }

  @Override
  public void run() {
    ((ProfilingContextIntegration) AgentTracer.get().getProfilingContext())
        .recordQueueingTime(System.currentTimeMillis() - creationTime);
    delegate.run();
  }
}
