package datadog.trace.agent.tooling.iast.stratum;

public class ParserException extends SourceMapException {
  /** */
  private static final long serialVersionUID = 4991227723777615317L;

  public ParserException() {}

  public ParserException(final String msg) {
    super(msg);
  }
}
