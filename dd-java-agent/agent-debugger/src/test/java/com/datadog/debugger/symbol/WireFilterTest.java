package com.datadog.debugger.symbol;

import static org.codehaus.groovy.runtime.InvokerHelper.asList;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WireFilterTest {
  @Test
  void filterOut() {
    WireFilter wireFilter = new WireFilter();
    assertFalse(wireFilter.filterOut(null));
    Scope scope = Scope.builder(ScopeType.CLASS, "", 0, 0).build();
    assertFalse(wireFilter.filterOut(scope));
    scope = Scope.builder(ScopeType.CLASS, "", 0, 0).name("com.squareup.wire.MyClass").build();
    assertFalse(wireFilter.filterOut(scope));
    scope =
        Scope.builder(ScopeType.CLASS, "", 0, 0)
            .languageSpecifics(
                new LanguageSpecifics.Builder()
                    .addInterfaces(asList("com.squareup.wire.Message"))
                    .build())
            .build();
    assertFalse(wireFilter.filterOut(scope));
    scope =
        Scope.builder(ScopeType.CLASS, "", 0, 0)
            .languageSpecifics(
                new LanguageSpecifics.Builder()
                    .superClass("com.squareup.wire.ProtoAdapter")
                    .build())
            .build();
    assertTrue(wireFilter.filterOut(scope));
    scope =
        Scope.builder(ScopeType.CLASS, "", 0, 0)
            .languageSpecifics(
                new LanguageSpecifics.Builder()
                    .addInterfaces(asList("com.squareup.wire.ProtoAdapter"))
                    .build())
            .build();
    assertTrue(wireFilter.filterOut(scope));
  }
}
