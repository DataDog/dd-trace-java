package datadog.trace.agent.tooling.iast.stratum;

import java.util.ArrayList;
import java.util.List;

public class EmbeddedStratum extends AbstractStratum {
  private final List<SourceMap> sourceMapList = new ArrayList<SourceMap>();

  public EmbeddedStratum() {
    this("");
  }

  public EmbeddedStratum(final String name) {
    super(name);
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
