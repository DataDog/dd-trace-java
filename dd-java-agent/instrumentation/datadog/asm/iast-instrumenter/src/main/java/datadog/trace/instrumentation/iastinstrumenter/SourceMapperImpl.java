package datadog.trace.instrumentation.iastinstrumenter;

import datadog.trace.agent.tooling.stratum.Stratum;
import datadog.trace.agent.tooling.stratum.StratumManager;
import datadog.trace.api.Config;
import datadog.trace.api.Pair;
import datadog.trace.api.iast.stratum.SourceMapper;
import datadog.trace.api.iast.telemetry.IastMetric;
import datadog.trace.api.iast.telemetry.IastMetricCollector;

public class SourceMapperImpl implements SourceMapper {

  // This is only available if IAST source mapping is enabled
  public static final SourceMapperImpl INSTANCE =
      Config.get().isIastSourceMappingEnabled()
          ? new SourceMapperImpl(
              new StratumManager(
                  Config.get().getIastSourceMappingMaxSize(),
                  SourceMapperImpl::onSourceMappingLimitReached))
          : null;

  private final StratumManager stratumManager;

  private SourceMapperImpl(StratumManager stratumManager) {
    this.stratumManager = stratumManager;
  }

  private static void onSourceMappingLimitReached(int maxSize) {
    IastMetricCollector.add(IastMetric.SOURCE_MAPPING_LIMIT_REACHED, 1);
  }

  @Override
  public Pair<String, Integer> getFileAndLine(String className, int lineNumber) {
    Stratum stratum = stratumManager.get(className);
    if (stratum == null) {
      return null;
    }
    Pair<String, Integer> inputLine = stratum.getInputLine(lineNumber);
    if (inputLine == null) {
      return null;
    }
    return Pair.of(stratum.getSourceFile(inputLine.getLeft()), inputLine.getRight());
  }
}
