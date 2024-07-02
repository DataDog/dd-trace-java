package datadog.trace.agent.tooling.iast.stratum.parser;

import datadog.trace.agent.tooling.iast.stratum.EmbeddedStratum;
import datadog.trace.agent.tooling.iast.stratum.ParserException;
import datadog.trace.agent.tooling.iast.stratum.SourceMap;
import datadog.trace.agent.tooling.iast.stratum.SourceMapException;
import datadog.trace.agent.tooling.iast.stratum.StratumExt;
import java.util.ArrayDeque;
import java.util.Deque;

class State {
  private SourceMap sourceMap;

  private StratumExt stratum;

  private EmbeddedStratum parentStratum;

  private final Deque<StackItem> stateStack = new ArrayDeque<>();

  int lineNumber;

  public void init() {
    lineNumber = 0;
    sourceMap = null;
    stratum = null;
    parentStratum = new EmbeddedStratum();
    stateStack.clear();
  }

  public EmbeddedStratum done() throws SourceMapException {
    if (!stateStack.isEmpty()) {
      throw new ParserException("Unbalanced source map");
    }
    return parentStratum;
  }

  public SourceMap getSourceMap() {
    return sourceMap;
  }

  void setSourceMap(final SourceMap sourceMap) throws SourceMapException {
    if (this.sourceMap != null) {
      throw new ParserException("End of source map expected");
    }
    this.sourceMap = sourceMap;
    stratum = null;
  }

  void endSourceMap() throws SourceMapException {
    if (sourceMap == null) {
      throw new ParserException("Unexpected end of source map");
    }
    sourceMap = null;
    stratum = null;
  }

  public StratumExt getStratum() throws SourceMapException {
    if (stratum == null) {
      throw new ParserException("Stratum expected");
    }
    return stratum;
  }

  void setStratum(final StratumExt stratum) throws SourceMapException {
    if (sourceMap == null) {
      throw new ParserException("Source map expected");
    }
    this.stratum = stratum;
  }

  void push(final EmbeddedStratum embeddedStratum) throws SourceMapException {
    stateStack.push(new StackItem(sourceMap, parentStratum));
    endSourceMap();
    setParentStratum(embeddedStratum);
  }

  void pop(final EmbeddedStratum embeddedStratum) throws SourceMapException {
    if (!parentStratum.getName().equals(embeddedStratum.getName())) {
      throw new ParserException("Invalid closing embedded stratum: " + embeddedStratum.getName());
    }
    StackItem item = stateStack.pop();
    setSourceMap(item.sourceMap);
    setParentStratum(item.parentStratum);
  }

  public EmbeddedStratum getParentStratum() {
    return parentStratum;
  }

  private void setParentStratum(final EmbeddedStratum parentStratum) {
    this.parentStratum = parentStratum;
  }

  private class StackItem {
    SourceMap sourceMap;

    EmbeddedStratum parentStratum;

    public StackItem(final SourceMap sourceMap, final EmbeddedStratum parentStratum) {
      this.sourceMap = sourceMap;
      this.parentStratum = parentStratum;
    }
  }
}
