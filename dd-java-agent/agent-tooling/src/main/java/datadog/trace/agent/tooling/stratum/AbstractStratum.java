package datadog.trace.agent.tooling.stratum;

public abstract class AbstractStratum {
  private String name;

  public AbstractStratum(final String name) {
    this.name = name;
  }

  public String getName() {
    return name;
  }

  public void setName(final String name) {
    this.name = name;
  }
}
