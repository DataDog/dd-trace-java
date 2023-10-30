package com.datadog.debugger.symbol;

import com.squareup.moshi.Json;

public class Symbol {
  @Json(name = "symbol_type")
  private final SymbolType symbolType;

  private final String name;
  private final int line;
  private final String type;

  public Symbol(SymbolType symbolType, String name, int line, String type) {
    this.symbolType = symbolType;
    this.name = name;
    this.line = line;
    this.type = type;
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
        + '}';
  }
}
