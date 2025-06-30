package com.datadog.debugger.symbol;

import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ProtoFilterTest {
  @Test
  void filterOut() {
    ProtoFilter protoFilter = new ProtoFilter();
    assertFalse(protoFilter.filterOut(null));
    Scope scope = Scope.builder(ScopeType.CLASS, "", 0, 0).build();
    assertFalse(protoFilter.filterOut(scope));
    scope = Scope.builder(ScopeType.CLASS, "", 0, 0).name("com.google.protobuf.MyClass").build();
    assertFalse(protoFilter.filterOut(scope));
    scope =
        Scope.builder(ScopeType.CLASS, "", 0, 0)
            .languageSpecifics(
                new LanguageSpecifics.Builder()
                    .addInterfaces(asList("com.google.protobuf.MessageOrBuilder"))
                    .build())
            .build();
    assertTrue(protoFilter.filterOut(scope));
    scope =
        Scope.builder(ScopeType.CLASS, "", 0, 0)
            .languageSpecifics(
                new LanguageSpecifics.Builder()
                    .superClass("com.google.protobuf.AbstractParser")
                    .build())
            .build();
    assertTrue(protoFilter.filterOut(scope));
    scope =
        Scope.builder(ScopeType.CLASS, "", 0, 0)
            .languageSpecifics(
                new LanguageSpecifics.Builder()
                    .superClass("com.google.protobuf.GeneratedMessageV3$Builder")
                    .build())
            .build();
    assertTrue(protoFilter.filterOut(scope));
    scope =
        Scope.builder(ScopeType.CLASS, "", 0, 0)
            .symbols(
                asList(
                    new Symbol(
                        SymbolType.STATIC_FIELD,
                        "SCHEMA$",
                        0,
                        "com.google.protobuf.Descriptors",
                        null)))
            .build();
    assertTrue(protoFilter.filterOut(scope));
  }
}
