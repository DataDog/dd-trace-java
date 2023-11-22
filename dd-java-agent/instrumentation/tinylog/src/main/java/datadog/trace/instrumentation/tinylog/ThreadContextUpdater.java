package datadog.trace.instrumentation.tinylog;

import datadog.trace.agent.tooling.log.LogContextScopeListener;
import org.tinylog.ThreadContext;

public class ThreadContextUpdater extends LogContextScopeListener {
  @Override
  public void add(String key, String value) {
    ThreadContext.put(key, value);
  }

  @Override
  public void remove(String key) {
    ThreadContext.remove(key);
  }
}
