package datadog.trace.agent.test;

import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

public class NoInterfacesInTheMiddle extends FutureTask {
  public NoInterfacesInTheMiddle(Callable callable) {
    super(callable);
  }
}
