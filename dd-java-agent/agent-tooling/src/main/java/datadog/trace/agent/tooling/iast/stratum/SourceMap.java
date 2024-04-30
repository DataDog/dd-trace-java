package datadog.trace.agent.tooling.iast.stratum;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class SourceMap implements Cloneable {
  private String outputFileName;

  private String defaultStratumName;

  private final List<StratumExt> stratumList = new ArrayList<StratumExt>();

  private final List<EmbeddedStratum> embeddedStratumList = new ArrayList<EmbeddedStratum>();

  public SourceMap() {}

  public SourceMap(final String outputFileName, final String defaultStratumName) {
    this.outputFileName = outputFileName;
    this.defaultStratumName = defaultStratumName;
  }

  @Override
  public Object clone() {
    SourceMap sourceMap = new SourceMap(outputFileName, defaultStratumName);
    for (Iterator<StratumExt> iter = stratumList.iterator(); iter.hasNext(); ) {
      sourceMap.getStratumList().add((StratumExt) iter.next().clone());
    }
    for (Iterator<EmbeddedStratum> iter = embeddedStratumList.iterator(); iter.hasNext(); ) {
      sourceMap.getEmbeddedStratumList().add((EmbeddedStratum) iter.next().clone());
    }
    return sourceMap;
  }

  public boolean isResolved() {
    return embeddedStratumList.isEmpty();
  }

  public String getOutputFileName() {
    return outputFileName;
  }

  public void setOutputFileName(final String outputFileName) {
    this.outputFileName = outputFileName;
  }

  public String getDefaultStratumName() {
    return defaultStratumName;
  }

  public void setDefaultStratumName(final String defaultStratumName) {
    this.defaultStratumName = defaultStratumName;
  }

  public List<StratumExt> getStratumList() {
    return stratumList;
  }

  public void setStratumList(final List<StratumExt> stratumList) {
    this.stratumList.clear();
    if (stratumList != null) {
      this.stratumList.addAll(stratumList);
    }
  }

  public List<EmbeddedStratum> getEmbeddedStratumList() {
    return embeddedStratumList;
  }

  public void setEmbeddedStratumList(final List<EmbeddedStratum> embeddedStratumList) {
    this.embeddedStratumList.clear();
    if (embeddedStratumList != null) {
      this.embeddedStratumList.addAll(embeddedStratumList);
    }
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
