package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.context.TraceScope;
import java.io.Closeable;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Set;

public interface AgentScope extends TraceScope, Closeable {
  AgentSpan span();

  @Override
  void setAsyncPropagation(boolean value);

  @Override
  void close();

  interface Continuation extends TraceScope.Continuation {
    boolean isRegistered();

    WeakReference<Continuation> register(ReferenceQueue referenceQueue);

    void cancel(Set<WeakReference<?>> weakReferences);
  }
}
