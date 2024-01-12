import datadog.trace.agent.tooling.csi.CallSites;
import datadog.trace.api.iast.IastCallSites;

public class MockCallSites implements IastCallSites, CallSites {
  @Override
  public void accept(final Container container) {}
}
