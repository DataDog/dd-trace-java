package datadog.trace.agent.tooling.stratum;

import java.util.ArrayList;
import java.util.List;

public class SourceMap {
  private final String outputFileName;

  private final String defaultStratumName;

  private final List<StratumExt> stratumList = new ArrayList<>();

  private final List<EmbeddedStratum> embeddedStratumList = new ArrayList<>();

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
    for (StratumExt stratum : stratumList) {
      if (stratum.getName().compareTo(stratumName) == 0) {
        return stratum;
      }
    }
    return null;
  }
}
