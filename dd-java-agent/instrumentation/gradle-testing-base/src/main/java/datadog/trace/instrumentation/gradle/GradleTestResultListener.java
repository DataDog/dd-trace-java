package datadog.trace.instrumentation.gradle;

import datadog.trace.api.civisibility.TestEventsBridge;
import java.util.Objects;

public class GradleTestResultListener {

  public static final GradleTestResultListener INSTANCE = new GradleTestResultListener();

  private Object rootTestId;

  public void onTestStarted(Object testId) {
    if (rootTestId == null) {
      rootTestId = testId;
      TestEventsBridge.onTestModuleStarted();
    }
  }

  public void onTestFinished(Object testId) {
    if (Objects.equals(rootTestId, testId)) {
      TestEventsBridge.onTestModuleFinished();
      rootTestId = null;
    }
  }
}
