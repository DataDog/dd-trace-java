package com.datadog.debugger.symbol;

public class AvroFilter implements ScopeFilter {
  @Override
  public boolean filterOut(Scope scope) {
    if (scope == null) {
      return false;
    }
    LanguageSpecifics languageSpecifics = scope.getLanguageSpecifics();
    if (languageSpecifics != null) {
      String superClass = languageSpecifics.getSuperClass();
      // Allow Avro data classes that extend SpecificRecordBase.
      if ("org.apache.avro.specific.SpecificRecordBase".equals(superClass)) {
        return false;
      }
    }
    // Filter out classes that appear to be just schema wrappers.
    if (scope.getScopeType() == ScopeType.CLASS
        && scope.getSymbols() != null
        && scope.getSymbols().stream()
            .anyMatch(
                it ->
                    it.getSymbolType() == SymbolType.STATIC_FIELD
                        && "SCHEMA$".equals(it.getName())
                        && it.getType() != null
                        && it.getType().contains("org.apache.avro.Schema"))) {
      return true;
    }
    // Otherwise, do not filter.
    return false;
  }
}
