import datadog.trace.agent.tooling.csi.CallSiteAdvice;
import datadog.trace.agent.tooling.csi.CallSites;
import datadog.trace.api.appsec.RaspCallSites;

public class MockRaspCallSites implements RaspCallSites, CallSites {
  @Override
  public void accept(final Container container) {
    container.addAdvice("type", "method", "descriptor", (CallSiteAdvice) null);
  }
}
