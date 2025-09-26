package datadog.trace.instrumentation.log4j2;

import datadog.trace.agent.tooling.log.LogContextScopeListener;
import org.apache.logging.log4j.ThreadContext;

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
