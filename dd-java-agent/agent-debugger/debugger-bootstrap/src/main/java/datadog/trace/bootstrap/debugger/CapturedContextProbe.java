package datadog.trace.bootstrap.debugger;

public interface CapturedContextProbe {
  boolean isCaptureSnapshot();

  boolean hasCondition();

  boolean isReadyToCapture();
}
