package com.datadog.debugger.symbol;

import java.util.List;

public class ProtoFilter implements ScopeFilter {
  @Override
  public boolean filterOut(Scope scope) {
    if (scope == null) {
      return false;
    }
    LanguageSpecifics languageSpecifics = scope.getLanguageSpecifics();
    if (languageSpecifics != null) {
      List<String> interfaces = languageSpecifics.getInterfaces();
      if (interfaces != null) {
        if (interfaces.contains("com.google.protobuf.MessageOrBuilder")) {
          // MessageOrBuilder is an interface implemented by both message classes and their
          // builders.
          // Scopes implementing this interface are filtered out because they do not represent
          // concrete data structures but rather interfaces for accessing or building messages.
          return true;
        }
      }
      String superClass = languageSpecifics.getSuperClass();
      if ("com.google.protobuf.AbstractParser".equals(superClass)) {
        // AbstractParser is a base class for parsing protobuf messages. Scopes with this super
        // class are filtered out because they are utility classes for parsing and do not contain
        // actual data fields.
        return true;
      }
      if ("com.google.protobuf.GeneratedMessageV3$Builder".equals(superClass)) {
        // GeneratedMessageV3$Builder is a builder class for constructing GeneratedMessageV3
        // instances. These scopes are filtered out because they are used for building messages and
        // do not represent the final data structure.
        return true;
      }
    }
    // If none of the above matched, see if the class has a proto descriptor field. This is the case
    // for wrapper
    // classes (`OuterClass`) and `Enum` classes. They contain metadata, not data.
    if (hasProtoDescriptorField(scope)) {
      return true;
    }
    // Probably no protobuf, pass
    return false;
  }

  private boolean hasProtoDescriptorField(Scope scope) {
    return scope.getScopeType() == ScopeType.CLASS
        && scope.getSymbols() != null
        && scope.getSymbols().stream()
            .anyMatch(
                it ->
                    it.getSymbolType() == SymbolType.STATIC_FIELD
                        && it.getType() != null
                        && it.getType().contains("com.google.protobuf.Descriptors"));
  }
}
