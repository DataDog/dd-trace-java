package datadog.trace.instrumentation.weaver;

import java.util.concurrent.ConcurrentLinkedQueue;
import sbt.testing.TaskDef;

public final class TaskDefAwareConcurrentLinkedQueueProxy<T> extends ConcurrentLinkedQueue<T> {

  private final TaskDef taskDef;
  private final ConcurrentLinkedQueue<T> delegate;

  public TaskDefAwareConcurrentLinkedQueueProxy(
      TaskDef taskDef, ConcurrentLinkedQueue<T> delegate) {
    super();
    this.taskDef = taskDef;
    this.delegate = delegate;
    DatadogWeaverReporter.start();
  }

  @Override
  public T poll() {
    T event = delegate.poll();
    DatadogWeaverReporter.processEvent(event, taskDef);
    return event;
  }
}
