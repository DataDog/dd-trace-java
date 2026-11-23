package com.datadog.debugger.symbol;

public class JythonFilter implements ScopeFilter {
  @Override
  public boolean filterOut(Scope scope) {
    if (scope == null) {
      return false;
    }
    if (scope.getScopeType() == ScopeType.CLASS
        && scope.getName() != null
        && scope.getName().startsWith("org.python.pycode.")) {
      return true;
    }
    return false;
  }
}
