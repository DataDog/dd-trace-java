package datadog.trace.bootstrap.instrumentation.jfr.queueing;

import datadog.trace.bootstrap.instrumentation.jfr.ContextualEvent;
import jdk.jfr.*;

@Name("datadog.QueueTime")
@Label("QueueTime")
@Description("Datadog queueing time event.")
@Category("Datadog")
@StackTrace(false)
public class QueueTimeEvent extends Event implements ContextualEvent, AutoCloseable {

  @Label("Local Root Span Id")
  private long localRootSpanId;

  @Label("Span Id")
  private long spanId;

  @Label("Origin")
  private Thread originThread;

  @Label("Task")
  private Class<?> task;

  @Label("Queue")
  private Class<?> queue;

  @Label("Executor")
  private Class<?> executor;

  public QueueTimeEvent(Class<?> task, Class<?> queue, Class<?> executor) {
    this.task = task;
    this.queue = queue;
    this.executor = executor;
    this.originThread = Thread.currentThread();
    captureContext();
    begin();
  }

  @Override
  public void close() {
    end();
    if (shouldCommit()) {
      commit();
    }
  }

  @Override
  public void setContext(long localRootSpanId, long spanId) {
    this.localRootSpanId = localRootSpanId;
    this.spanId = spanId;
  }
}
