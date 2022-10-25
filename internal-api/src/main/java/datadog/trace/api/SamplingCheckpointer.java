package datadog.trace.api;

import static datadog.trace.api.Checkpointer.CPU;
import static datadog.trace.api.Checkpointer.END;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SamplingCheckpointer implements SpanCheckpointer {

  /**
   * Creates a new, pre-configured instance bound to {@linkplain NoOpCheckpointer#NO_OP}.<br>
   * A different {@linkplain Checkpointer} implementation can be set via {@linkplain
   * SamplingCheckpointer#register(Checkpointer)}.
   *
   * @return a new, pre-configured instance
   */
  public static SamplingCheckpointer create() {
    return new SamplingCheckpointer(NoOpCheckpointer.NO_OP);
  }

  private static final Logger log = LoggerFactory.getLogger(SamplingCheckpointer.class);

  private static final AtomicReferenceFieldUpdater<SamplingCheckpointer, Checkpointer> CAS =
      AtomicReferenceFieldUpdater.newUpdater(
          SamplingCheckpointer.class, Checkpointer.class, "checkpointer");

  private volatile Checkpointer checkpointer;

  public SamplingCheckpointer(final Checkpointer checkpointer) {
    this.checkpointer = checkpointer;
  }

  public void register(final Checkpointer checkpointer) {
    if (!CAS.compareAndSet(this, NoOpCheckpointer.NO_OP, checkpointer)) {
      log.debug(
          "failed to register checkpointer {} - {} already registered",
          checkpointer.getClass(),
          this.checkpointer.getClass());
    } else {
      log.debug("Registered checkpointer implementation: {}", checkpointer);
    }
  }

  @Override
  public void checkpoint(final AgentSpan span, final int flags) {
    if (!span.eligibleForDropping()) {
      checkpointer.checkpoint(span, flags);
    }
  }

  @Override
  public void onStartWork(final AgentSpan span) {
    checkpoint(span, CPU);
  }

  @Override
  public void onFinishWork(final AgentSpan span) {
    checkpoint(span, CPU | END);
  }

  @Override
  public void onRootSpanFinished(final AgentSpan rootSpan, final boolean published) {
    final Boolean emittingCheckpoints = rootSpan.isEmittingCheckpoints();
    checkpointer.onRootSpanWritten(
        rootSpan, published, emittingCheckpoints != null && emittingCheckpoints);
  }

  @Override
  public void onRootSpanStarted(AgentSpan root) {
    checkpointer.onRootSpanStarted(root);
  }

  private static final class NoOpCheckpointer implements Checkpointer {

    static final NoOpCheckpointer NO_OP = new NoOpCheckpointer();

    @Override
    public void checkpoint(final AgentSpan span, final int flags) {}

    @Override
    public void onRootSpanWritten(
        final AgentSpan rootSpan, final boolean published, final boolean checkpointsSampled) {}

    @Override
    public void onRootSpanStarted(AgentSpan rootSpan) {}
  }
}
