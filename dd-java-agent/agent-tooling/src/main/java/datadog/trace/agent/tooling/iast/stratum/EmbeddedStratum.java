package datadog.trace.agent.tooling.iast.stratum;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class EmbeddedStratum extends AbstractStratum implements Cloneable {
  private final List<SourceMap> sourceMapList = new ArrayList<SourceMap>();

  public EmbeddedStratum() {
    this("");
  }

  public EmbeddedStratum(final String name) {
    super(name);
  }

  @Override
  public Object clone() {
    EmbeddedStratum embeddedStratum = new EmbeddedStratum(getName());
    for (Iterator<SourceMap> iter = sourceMapList.iterator(); iter.hasNext(); ) {
      embeddedStratum.getSourceMapList().add((SourceMap) iter.next().clone());
    }
    return embeddedStratum;
  }

  public List<SourceMap> getSourceMapList() {
    return sourceMapList;
  }

  public void setSourceMapList(final List<SourceMap> sourceMapList) {
    this.sourceMapList.clear();
    if (sourceMapList != null) {
      this.sourceMapList.addAll(sourceMapList);
    }
  }
}
