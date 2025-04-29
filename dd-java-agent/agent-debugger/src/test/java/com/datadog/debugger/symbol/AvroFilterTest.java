package com.datadog.debugger.symbol;

import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AvroFilterTest {
  @Test
  void filterOut() {
    AvroFilter avroFilter = new AvroFilter();
    assertFalse(avroFilter.filterOut(null));
    Scope scope = Scope.builder(ScopeType.CLASS, "", 0, 0).build();
    assertFalse(avroFilter.filterOut(scope));
    scope = Scope.builder(ScopeType.CLASS, "", 0, 0).name("org.apache.avro.MyClass").build();
    assertFalse(avroFilter.filterOut(scope));
    scope =
        Scope.builder(ScopeType.CLASS, "", 0, 0)
            .languageSpecifics(
                new LanguageSpecifics.Builder()
                    .superClass("org.apache.avro.specific.SpecificRecordBase")
                    .build())
            .build();
    assertFalse(avroFilter.filterOut(scope));
    scope =
        Scope.builder(ScopeType.CLASS, "", 0, 0)
            .symbols(
                asList(
                    new Symbol(
                        SymbolType.STATIC_FIELD, "SCHEMA$", 0, "org.apache.avro.Schema", null)))
            .build();
    assertTrue(avroFilter.filterOut(scope));
  }
}
