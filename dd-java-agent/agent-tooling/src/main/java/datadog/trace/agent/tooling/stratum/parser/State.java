package datadog.trace.agent.tooling.stratum.parser;

import datadog.trace.agent.tooling.stratum.EmbeddedStratum;
import datadog.trace.agent.tooling.stratum.SourceMap;
import datadog.trace.agent.tooling.stratum.StratumExt;
import java.util.ArrayDeque;
import java.util.Deque;

class State {
  private SourceMap sourceMap;

  private StratumExt stratum;

  private EmbeddedStratum parentStratum = new EmbeddedStratum();

  private final Deque<StackItem> stateStack = new ArrayDeque<>();

  int lineNumber;

  public EmbeddedStratum done() {
    if (!stateStack.isEmpty()) {
      throw new IllegalStateException("Unbalanced source map");
    }
    return parentStratum;
  }

  public SourceMap getSourceMap() {
    return sourceMap;
  }

  void setSourceMap(final SourceMap sourceMap) {
    if (this.sourceMap != null) {
      throw new IllegalStateException("End of source map expected");
    }
    this.sourceMap = sourceMap;
    stratum = null;
  }

  void endSourceMap() {
    if (sourceMap == null) {
      throw new IllegalStateException("Unexpected end of source map");
    }
    sourceMap = null;
    stratum = null;
  }

  public StratumExt getStratum() {
    if (stratum == null) {
      throw new IllegalStateException("Stratum expected");
    }
    return stratum;
  }

  void setStratum(final StratumExt stratum) {
    if (sourceMap == null) {
      throw new IllegalStateException("Source map expected");
    }
    this.stratum = stratum;
  }

  void push(final EmbeddedStratum embeddedStratum) {
    stateStack.push(new StackItem(sourceMap, parentStratum));
    endSourceMap();
    setParentStratum(embeddedStratum);
  }

  void pop(final EmbeddedStratum embeddedStratum) {
    if (!parentStratum.getName().equals(embeddedStratum.getName())) {
      throw new IllegalArgumentException(
          "Invalid closing embedded stratum: " + embeddedStratum.getName());
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
