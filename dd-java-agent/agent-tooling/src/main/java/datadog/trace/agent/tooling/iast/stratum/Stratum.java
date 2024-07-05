package datadog.trace.agent.tooling.iast.stratum;

import datadog.trace.api.Pair;

public interface Stratum {

  /**
   * Returns the input line number and the input file id for the given output line number.
   *
   * @param outputLineNumber
   * @return
   */
  Pair<Integer, Integer> getInputLine(final int outputLineNumber);

  /**
   * Returns the source file for the given file id.
   *
   * @param fileId
   * @return
   */
  String getSourceFile(final int fileId);
}
