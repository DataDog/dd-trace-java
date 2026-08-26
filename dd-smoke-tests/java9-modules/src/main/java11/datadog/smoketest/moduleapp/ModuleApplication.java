package datadog.smoketest.moduleapp;

import testdog.moduleapp.LambdaTask;

public class ModuleApplication {
  public static void main(final String[] args) throws InterruptedException {
    LambdaTask.runOnExecutor();
    Thread.sleep(600);
  }
}
