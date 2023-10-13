package datadog.trace.api;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EndpointCheckpointerHolder implements EndpointCheckpointer {

  /**
   * Creates a new, pre-configured instance bound to {@linkplain NoOpCheckpointer#NO_OP}.<br>
   * A different {@linkplain EndpointCheckpointer} implementation can be set via {@linkplain
   * EndpointCheckpointerHolder#register(EndpointCheckpointer)}.
   *
   * @return a new, pre-configured instance
   */
  public static EndpointCheckpointerHolder create() {
    return new EndpointCheckpointerHolder(NoOpCheckpointer.NO_OP);
  }

  private static final Logger log = LoggerFactory.getLogger(EndpointCheckpointerHolder.class);
  private static final AtomicReferenceFieldUpdater<EndpointCheckpointerHolder, EndpointCheckpointer>
      ROOT_SPAN_CHECKPOINTER =
          AtomicReferenceFieldUpdater.newUpdater(
              EndpointCheckpointerHolder.class, EndpointCheckpointer.class, "endpointCheckpointer");
  private volatile EndpointCheckpointer endpointCheckpointer;

  public EndpointCheckpointerHolder(EndpointCheckpointer endpointCheckpointer) {
    this.endpointCheckpointer = endpointCheckpointer;
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
  public void onRootSpanFinished(AgentSpan rootSpan, EndpointTracker tracker) {
    endpointCheckpointer.onRootSpanFinished(rootSpan, tracker);
  }

  @Override
  public EndpointTracker onRootSpanStarted(AgentSpan root) {
    return endpointCheckpointer.onRootSpanStarted(root);
  }

  private static final class NoOpCheckpointer implements EndpointCheckpointer {

    static final NoOpCheckpointer NO_OP = new NoOpCheckpointer();

    @Override
    public void onRootSpanFinished(AgentSpan rootSpan, EndpointTracker tracker) {}

    @Override
    public EndpointTracker onRootSpanStarted(AgentSpan rootSpan) {
      return null;
    }
  }
}
