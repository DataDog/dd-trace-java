package datadog.trace.agent.test;

import java.util.concurrent.Callable;

public class LeafFutureTask extends NoInterfacesInTheMiddle {
  public LeafFutureTask(Callable callable) {
    super(callable);
  }
}
