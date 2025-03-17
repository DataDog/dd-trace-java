import static datadog.trace.agent.tooling.csi.CallSiteAdvice.AdviceType.BEFORE;

import datadog.trace.agent.tooling.csi.CallSiteAdvice;
import datadog.trace.agent.tooling.csi.CallSites;
import datadog.trace.api.appsec.RaspCallSites;

public class MockRaspCallSites implements RaspCallSites, CallSites {
  @Override
  public void accept(final Container container) {
    container.addAdvice(BEFORE, "typeRasp", "methodRasp", "descriptorRasp", (CallSiteAdvice) null);
  }
}
