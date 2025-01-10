package datadog.trace.instrumentation.weaver;

import java.util.concurrent.ConcurrentLinkedQueue;
import sbt.testing.TaskDef;
import weaver.framework.RunEvent;
import weaver.framework.SuiteFinished;
import weaver.framework.SuiteStarted;
import weaver.framework.TestFinished;

public final class TaskDefAwareQueueProxy<T> extends ConcurrentLinkedQueue<T> {

  private final TaskDef taskDef;
  private final ConcurrentLinkedQueue<T> delegate;

  public TaskDefAwareQueueProxy(TaskDef taskDef, ConcurrentLinkedQueue<T> delegate) {
    this.taskDef = taskDef;
    this.delegate = delegate;
    DatadogWeaverReporter.start();
  }

  @Override
  public T poll() {
    T event = delegate.poll();
    if (event instanceof RunEvent) {
      // handle event here, using taskDef reference to get suite details
      if (event instanceof SuiteStarted) {
        DatadogWeaverReporter.onSuiteStart((SuiteStarted) event);
      } else if (event instanceof SuiteFinished) {
        DatadogWeaverReporter.onSuiteFinish((SuiteFinished) event);
      } else if (event instanceof TestFinished) {
        DatadogWeaverReporter.onTestFinished((TestFinished) event, taskDef);
      }
    }
    return event;
  }
}
