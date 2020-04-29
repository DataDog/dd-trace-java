package datadog.trace.bootstrap.instrumentation.api;

public class ParentChildSpan {

  private final AgentSpan parent;
  private final AgentSpan child;

  public ParentChildSpan(AgentSpan parent, AgentSpan child) {
    this.parent = parent;
    this.child = child;
  }

  public AgentSpan getParent() {
    return parent;
  }

  public AgentSpan getChild() {
    return child;
  }

  public boolean hasParent() {
    return null != parent;
  }

  public boolean hasChild() {
    return null != child;
  }
}
