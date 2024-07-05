package datadog.trace.agent.tooling.iast.stratum;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class SourceMap {
  private String outputFileName;

  private String defaultStratumName;

  private final List<StratumExt> stratumList = new ArrayList<StratumExt>();

  private final List<EmbeddedStratum> embeddedStratumList = new ArrayList<EmbeddedStratum>();

  public SourceMap(final String outputFileName, final String defaultStratumName) {
    this.outputFileName = outputFileName;
    this.defaultStratumName = defaultStratumName;
  }

  public boolean isResolved() {
    return embeddedStratumList.isEmpty();
  }

  public String getOutputFileName() {
    return outputFileName;
  }

  public String getDefaultStratumName() {
    return defaultStratumName;
  }

  public List<StratumExt> getStratumList() {
    return stratumList;
  }

  public List<EmbeddedStratum> getEmbeddedStratumList() {
    return embeddedStratumList;
  }

  public StratumExt getStratum(final String stratumName) {
    for (Iterator<StratumExt> iter = stratumList.iterator(); iter.hasNext(); ) {
      StratumExt stratum = iter.next();
      if (stratum.getName().compareTo(stratumName) == 0) {
        return stratum;
      }
    }
    return null;
  }
}
