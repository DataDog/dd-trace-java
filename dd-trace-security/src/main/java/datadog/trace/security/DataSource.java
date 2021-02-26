package datadog.trace.security;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.scopemanager.ContinuableScopeManager;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public interface DataSource {
  <T> T getData(Address<T> address);
  Set<String> getAllAddressKeys();

  enum EmptyDataSource implements DataSource {
    INSTANCE;

    @Override
    public <T> T getData(Address<T> address) {
      return null;
    }

    @Override
    public Set<String> getAllAddressKeys() {
      return Collections.emptySet();
    }

    @Override
    public String toString() {
      return "<empty DataSource>";
    }
  }

  class SpanDataSource implements DataSource {
    private final AgentSpan span;

    public SpanDataSource(AgentSpan span) {
      this.span = span;
    }


    @Override
    public <T> T getData(Address<T> address) {
      return (T) span.getTag(address.getKey());
    }

    @Override
    public Set<String> getAllAddressKeys() {
      return span.getTags().keySet();
    }
  }

  class ScopeStackDataSource implements DataSource {
    private final ContinuableScopeManager.ScopeStack scopeStack;

    public ScopeStackDataSource(ContinuableScopeManager.ScopeStack scopeStack) {
      this.scopeStack = scopeStack;
    }

    @Override
    public <T> T getData(Address<T> address) {
      for (ContinuableScopeManager.ContinuableScope scope : scopeStack.stack) {
        if (!scope.alive()) {
          continue;
        }
        AgentSpan span = scope.span();
        Object tag = span.getTag(address.getKey());
        if (tag != null) {
          return (T) tag;
        }
      }

      return null;
    }

    @Override
    public Set<String> getAllAddressKeys() {
      Set<String> keys = new HashSet<>();
      for (ContinuableScopeManager.ContinuableScope scope : scopeStack.stack) {
        if (!scope.alive()) {
          continue;
        }
        AgentSpan span = scope.span();
        keys.addAll(span.getTags().keySet());
      }
      return keys;
    }
  }
}
