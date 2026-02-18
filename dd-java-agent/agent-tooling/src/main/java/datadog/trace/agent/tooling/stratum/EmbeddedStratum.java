package datadog.trace.agent.tooling.stratum;

import java.util.ArrayList;
import java.util.List;

public class EmbeddedStratum extends AbstractStratum {
  private final List<SourceMap> sourceMapList = new ArrayList<>();

  public EmbeddedStratum() {
    this("");
  }

  public EmbeddedStratum(final String name) {
    super(name);
  }

  public List<SourceMap> getSourceMapList() {
    return sourceMapList;
  }
}
