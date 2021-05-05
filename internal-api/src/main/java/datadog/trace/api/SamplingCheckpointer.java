package datadog.trace.api;

import static datadog.trace.api.Checkpointer.CPU;
import static datadog.trace.api.Checkpointer.END;
import static datadog.trace.api.Checkpointer.SPAN;
import static datadog.trace.api.Checkpointer.THREAD_MIGRATION;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SamplingCheckpointer implements SpanCheckpointer {

  public static SamplingCheckpointer create() {
    return new SamplingCheckpointer(NoOpCheckpointer.NO_OP);
  }

  private static final Logger log = LoggerFactory.getLogger(SamplingCheckpointer.class);

  private static final AtomicReferenceFieldUpdater<SamplingCheckpointer, Checkpointer> CAS =
      AtomicReferenceFieldUpdater.newUpdater(
          SamplingCheckpointer.class, Checkpointer.class, "checkpointer");

  private volatile Checkpointer checkpointer;

  public SamplingCheckpointer(Checkpointer checkpointer) {
    this.checkpointer = checkpointer;
  }

  public void register(Checkpointer checkpointer) {
    if (!CAS.compareAndSet(this, NoOpCheckpointer.NO_OP, checkpointer)) {
      log.debug(
          "failed to register checkpointer {} - {} already registered",
          checkpointer.getClass(),
          this.checkpointer.getClass());
    }
  }

  @Override
  public void onComplexEvent(AgentSpan span, int flags) {
    checkpoint(span, flags);
  }

  @Override
  public void onStart(AgentSpan span) {
    checkpoint(span, SPAN);
  }

  @Override
  public void onCommenceWork(AgentSpan span) {
    checkpoint(span, CPU);
  }

  @Override
  public void onCompleteWork(AgentSpan span) {
    checkpoint(span, CPU | END);
  }

  @Override
  public void onThreadMigration(AgentSpan span) {
    checkpoint(span, THREAD_MIGRATION);
  }

  @Override
  public void onAsyncResume(AgentSpan span) {
    checkpoint(span, THREAD_MIGRATION | END);
  }

  @Override
  public void onFinish(AgentSpan span) {
    checkpoint(span, SPAN | END);
  }

  private void checkpoint(AgentSpan span, int flags) {
    if (!span.eligibleForDropping()) {
      checkpointer.checkpoint(span.getTraceId(), span.getSpanId(), flags);
    }
  }

  private static final class NoOpCheckpointer implements Checkpointer {

    static final NoOpCheckpointer NO_OP = new NoOpCheckpointer();

    @Override
    public void checkpoint(DDId traceId, DDId spanId, int flags) {}
  }
}
