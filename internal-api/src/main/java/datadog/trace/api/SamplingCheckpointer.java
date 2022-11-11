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
    return new SamplingCheckpointer(NoOpCheckpointer.NO_OP, NoOpCheckpointer.NO_OP);
  }

  private static final Logger log = LoggerFactory.getLogger(SamplingCheckpointer.class);

  private static final AtomicReferenceFieldUpdater<SamplingCheckpointer, Checkpointer>
      CHECKPOINTER =
          AtomicReferenceFieldUpdater.newUpdater(
              SamplingCheckpointer.class, Checkpointer.class, "checkpointer");
  private static final AtomicReferenceFieldUpdater<SamplingCheckpointer, EndpointCheckpointer>
      ROOT_SPAN_CHECKPOINTER =
          AtomicReferenceFieldUpdater.newUpdater(
              SamplingCheckpointer.class, EndpointCheckpointer.class, "endpointCheckpointer");

  @Deprecated(/*forRemoval = true*/ ) private volatile Checkpointer checkpointer;
  private volatile EndpointCheckpointer endpointCheckpointer;

  public SamplingCheckpointer(
      Checkpointer checkpointer, EndpointCheckpointer endpointCheckpointer) {
    this.checkpointer = checkpointer;
    this.endpointCheckpointer = endpointCheckpointer;
  }

  @Deprecated(/*forRemoval = true*/ )
  public void register(final Checkpointer checkpointer) {
    if (!CHECKPOINTER.compareAndSet(this, NoOpCheckpointer.NO_OP, checkpointer)) {
      log.debug(
          "failed to register checkpointer {} - {} already registered",
          checkpointer.getClass(),
          this.checkpointer.getClass());
    } else {
      log.debug("Registered checkpointer implementation: {}", checkpointer);
    }
  }

  public void register(EndpointCheckpointer endpointCheckpointer) {
    if (!ROOT_SPAN_CHECKPOINTER.compareAndSet(this, NoOpCheckpointer.NO_OP, endpointCheckpointer)) {
      log.debug(
          "failed to register root span checkpointer {} - {} already registered",
          endpointCheckpointer.getClass(),
          this.endpointCheckpointer.getClass());
    } else {
      log.debug("Registered root span checkpointer implementation: {}", endpointCheckpointer);
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
    endpointCheckpointer.onRootSpanFinished(rootSpan, published);
  }

  @Override
  public void onRootSpanStarted(AgentSpan root) {
    endpointCheckpointer.onRootSpanStarted(root);
  }

  private static final class NoOpCheckpointer implements Checkpointer, EndpointCheckpointer {

    static final NoOpCheckpointer NO_OP = new NoOpCheckpointer();

    @Override
    public void checkpoint(final AgentSpan span, final int flags) {}

    @Override
    public void onRootSpanFinished(final AgentSpan rootSpan, final boolean published) {}

    @Override
    public void onRootSpanStarted(AgentSpan rootSpan) {}
  }
}
