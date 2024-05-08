package datadog.trace.instrumentation.iastinstrumenter;

import datadog.trace.agent.tooling.iast.stratum.Stratum;
import datadog.trace.agent.tooling.iast.stratum.StratumManagerImpl;
import datadog.trace.api.Pair;
import datadog.trace.api.iast.stratum.SourceMapper;

public class SourceMapperImpl implements SourceMapper {

  public static final SourceMapperImpl INSTANCE = new SourceMapperImpl();

  private SourceMapperImpl() {
    // Prevent instantiation
  }

  @Override
  public Pair<String, Integer> getFileAndLine(String className, int lineNumber) {
    Stratum stratum = StratumManagerImpl.INSTANCE.get(className);
    if (stratum != null) {
      return Pair.of(stratum.getSourceFile(), stratum.getInputLineNumber(lineNumber));
    }
    return null;
  }
}
