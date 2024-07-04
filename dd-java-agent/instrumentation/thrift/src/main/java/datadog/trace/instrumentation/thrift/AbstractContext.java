package datadog.trace.instrumentation.thrift;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

public abstract class AbstractContext {
  public String methodName;
  public long startTime = 0L;
  public boolean createdSpan = false;

  public abstract String getArguments();

  public abstract String getOperatorName();

  public final void setup(String methodName) {
    this.methodName = methodName;
    this.startTime = MILLISECONDS.toMicros(System.currentTimeMillis());
  }

  public boolean isCreatedSpan() {
    return createdSpan;
  }

  public void setCreatedSpan(boolean createdSpan) {
    this.createdSpan = createdSpan;
  }
}
