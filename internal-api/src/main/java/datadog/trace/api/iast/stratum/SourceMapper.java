package datadog.trace.api.iast.stratum;

import datadog.trace.api.Pair;

public interface SourceMapper {

  Pair<String, Integer> getFileAndLine(String className, int lineNumber);
}
