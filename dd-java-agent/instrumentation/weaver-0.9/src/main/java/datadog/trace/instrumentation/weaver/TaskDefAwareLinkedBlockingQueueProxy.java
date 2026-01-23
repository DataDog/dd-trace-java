package datadog.trace.instrumentation.weaver;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import sbt.testing.TaskDef;

public final class TaskDefAwareLinkedBlockingQueueProxy<T> extends LinkedBlockingQueue<T> {

  private final TaskDef taskDef;
  private final LinkedBlockingQueue<T> delegate;

  public TaskDefAwareLinkedBlockingQueueProxy(TaskDef taskDef, LinkedBlockingQueue<T> delegate) {
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

  @Override
  public T poll(long timeout, TimeUnit unit) throws InterruptedException {
    T event = delegate.poll(timeout, unit);
    if (event != null) {
      DatadogWeaverReporter.processEvent(event, taskDef);
    }
    return event;
  }
}
