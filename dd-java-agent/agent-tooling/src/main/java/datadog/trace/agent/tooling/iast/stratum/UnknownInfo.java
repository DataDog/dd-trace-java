package datadog.trace.agent.tooling.iast.stratum;

public class UnknownInfo implements Cloneable {
  private final String[] data;

  public UnknownInfo(final String[] data) {
    this.data = data;
  }

  @Override
  public Object clone() {
    return new UnknownInfo(data.clone());
  }

  public String[] getData() {
    return data;
  }
}
