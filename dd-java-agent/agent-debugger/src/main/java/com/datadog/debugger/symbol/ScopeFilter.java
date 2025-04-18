package com.datadog.debugger.symbol;

public interface ScopeFilter {
  /** returns true if the scope should be excluded */
  boolean filterOut(Scope scope);
}
