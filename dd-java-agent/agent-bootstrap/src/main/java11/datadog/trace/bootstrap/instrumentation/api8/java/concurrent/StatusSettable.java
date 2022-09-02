package datadog.trace.bootstrap.instrumentation.api8.java.concurrent;

public interface StatusSettable<C> {
  C statusStart();

  void setSuccess(C context);

  void setError(C context, Throwable throwable);

  void statusFinished(C context);
}
