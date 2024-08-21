package datadog.trace.instrumentation.iastinstrumenter;

import datadog.trace.agent.tooling.iast.stratum.Stratum;
import datadog.trace.agent.tooling.iast.stratum.StratumManager;
import datadog.trace.api.Config;
import datadog.trace.api.Pair;
import datadog.trace.api.iast.stratum.SourceMapper;

public class SourceMapperImpl implements SourceMapper {

  // This is only available if IAST source mapping is enabled
  public static final SourceMapperImpl INSTANCE =
      Config.get().isIastSourceMappingEnabled()
          ? new SourceMapperImpl(StratumManager.INSTANCE)
          : null;

  private final StratumManager stratumManager;

  private SourceMapperImpl(StratumManager stratumManager) {
    // Prevent instantiation
    this.stratumManager = stratumManager;
  }

  @Override
  public Pair<String, Integer> getFileAndLine(String className, int lineNumber) {
    Stratum stratum = stratumManager.get(className);
    if (stratum == null) {
      return null;
    }
    Pair<Integer, Integer> inputLine = stratum.getInputLine(lineNumber);
    if (inputLine == null) {
      return null;
    }
    return Pair.of(stratum.getSourceFile(inputLine.getLeft()), inputLine.getRight());
  }
}
