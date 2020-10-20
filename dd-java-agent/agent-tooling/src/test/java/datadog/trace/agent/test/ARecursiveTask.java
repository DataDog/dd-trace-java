package datadog.trace.agent.test;

import java.util.concurrent.RecursiveTask;

public class ARecursiveTask extends RecursiveTask<Void> {
  @Override
  protected Void compute() {
    return null;
  }
}
