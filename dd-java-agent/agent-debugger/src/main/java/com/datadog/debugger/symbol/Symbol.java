package com.datadog.debugger.symbol;

import com.squareup.moshi.Json;

public class Symbol {
  @Json(name = "symbol_type")
  private final SymbolType symbolType;

  private final String name;
  private final int line;
  private final String type;

  @Json(name = "language_specifics")
  private final LanguageSpecifics languageSpecifics;

  public Symbol(
      SymbolType symbolType,
      String name,
      int line,
      String type,
      LanguageSpecifics languageSpecifics) {
    this.symbolType = symbolType;
    this.name = name;
    this.line = line;
    this.type = type;
    this.languageSpecifics = languageSpecifics;
  }

  public SymbolType getSymbolType() {
    return symbolType;
  }

  public String getName() {
    return name;
  }

  public int getLine() {
    return line;
  }

  public String getType() {
    return type;
  }

  public LanguageSpecifics getLanguageSpecifics() {
    return languageSpecifics;
  }

  @Override
  public String toString() {
    return "Symbol{"
        + "symbolType="
        + symbolType
        + ", name='"
        + name
        + '\''
        + ", line="
        + line
        + ", type='"
        + type
        + '\''
        + ", languageSpecifics="
        + languageSpecifics
        + '}';
  }
}
