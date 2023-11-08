package com.datadog.debugger.symbol;

import com.squareup.moshi.Json;
import java.util.List;

public class Scope {
  @Json(name = "scope_type")
  private final ScopeType scopeType;

  @Json(name = "source_file")
  private final String sourceFile;

  @Json(name = "start_line")
  private final int startLine;

  @Json(name = "end_line")
  private final int endLine;

  private final String name;

  @Json(name = "language_specifics")
  private final LanguageSpecifics languageSpecifics;

  private final List<Symbol> symbols;
  private final List<Scope> scopes;

  public Scope(
      ScopeType scopeType,
      String sourceFile,
      int startLine,
      int endLine,
      String name,
      LanguageSpecifics languageSpecifics,
      List<Symbol> symbols,
      List<Scope> scopes) {
    this.scopeType = scopeType;
    this.sourceFile = sourceFile;
    this.startLine = startLine;
    this.endLine = endLine;
    this.name = name;
    this.languageSpecifics = languageSpecifics;
    this.symbols = symbols;
    this.scopes = scopes;
  }

  public ScopeType getScopeType() {
    return scopeType;
  }

  public String getSourceFile() {
    return sourceFile;
  }

  public int getStartLine() {
    return startLine;
  }

  public int getEndLine() {
    return endLine;
  }

  public String getName() {
    return name;
  }

  public LanguageSpecifics getLanguageSpecifics() {
    return languageSpecifics;
  }

  public List<Symbol> getSymbols() {
    return symbols;
  }

  public List<Scope> getScopes() {
    return scopes;
  }

  @Override
  public String toString() {
    return "Scope{"
        + "scopeType="
        + scopeType
        + ", sourceFile='"
        + sourceFile
        + '\''
        + ", startLine="
        + startLine
        + ", endLine="
        + endLine
        + ", name='"
        + name
        + '\''
        + ", languageSpecifics="
        + languageSpecifics
        + ", symbols="
        + symbols
        + ", scopes="
        + scopes
        + '}';
  }

  public static Builder builder(
      ScopeType scopeType, String sourceFile, int startLine, int endLine) {
    return new Builder(scopeType, sourceFile, startLine, endLine);
  }

  public static class Builder {
    private final ScopeType scopeType;
    private final String sourceFile;
    private final int startLine;
    private final int endLine;
    private String name;
    private LanguageSpecifics languageSpecifics;
    private List<Symbol> symbols;
    private List<Scope> scopes;

    public Builder(ScopeType scopeType, String sourceFile, int startLine, int endLine) {
      this.scopeType = scopeType;
      this.sourceFile = sourceFile;
      this.startLine = startLine;
      this.endLine = endLine;
    }

    public Builder name(String name) {
      this.name = name;
      return this;
    }

    public Builder languageSpecifics(LanguageSpecifics languageSpecifics) {
      this.languageSpecifics = languageSpecifics;
      return this;
    }

    public Builder symbols(List<Symbol> symbols) {
      this.symbols = symbols;
      return this;
    }

    public Builder scopes(List<Scope> scopes) {
      this.scopes = scopes;
      return this;
    }

    public Scope build() {
      return new Scope(
          scopeType, sourceFile, startLine, endLine, name, languageSpecifics, symbols, scopes);
    }
  }
}
