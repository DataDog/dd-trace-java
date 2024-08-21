package datadog.trace.agent.tooling.iast.stratum;

public class SourceMapException extends Exception {
  /** */
  private static final long serialVersionUID = 254089927846131094L;

  public SourceMapException() {}

  public SourceMapException(final String msg) {
    super(msg);
  }
}
