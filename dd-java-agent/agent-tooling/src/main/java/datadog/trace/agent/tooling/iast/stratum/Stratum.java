package datadog.trace.agent.tooling.iast.stratum;

import datadog.trace.api.Pair;

public interface Stratum {

  /**
   * Returns the input line number and the input file id for the given output line number.
   *
   * @param outputLineNumber the class line number
   */
  Pair<Integer, Integer> getInputLine(final int outputLineNumber);

  /** Returns the source file for the given file id. */
  String getSourceFile(final int fileId);
}
