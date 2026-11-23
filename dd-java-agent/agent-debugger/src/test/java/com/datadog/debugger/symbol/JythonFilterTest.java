package com.datadog.debugger.symbol;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class JythonFilterTest {
  @Test
  void filterOut() {
    JythonFilter jythonFilter = new JythonFilter();
    assertFalse(jythonFilter.filterOut(null));
    Scope scope = Scope.builder(ScopeType.CLASS, "", 0, 0).build();
    assertFalse(jythonFilter.filterOut(scope));
    scope = Scope.builder(ScopeType.CLASS, "", 0, 0).name("org.python.core.PyObject").build();
    assertFalse(jythonFilter.filterOut(scope));
    scope = Scope.builder(ScopeType.METHOD, "", 0, 0).name("org.python.pycode._pyx0").build();
    assertFalse(jythonFilter.filterOut(scope));
    scope = Scope.builder(ScopeType.CLASS, "", 0, 0).name("org.python.pycode._pyx0").build();
    assertTrue(jythonFilter.filterOut(scope));
  }
}
