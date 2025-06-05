import static datadog.trace.agent.tooling.csi.CallSiteAdvice.AdviceType.BEFORE;

import datadog.trace.agent.tooling.csi.CallSiteAdvice;
import datadog.trace.agent.tooling.csi.CallSites;
import datadog.trace.api.iast.IastCallSites;

public class MockCallSites implements IastCallSites, CallSites {
  @Override
  public void accept(final Container container) {
    container.addAdvice(BEFORE, "type", "method", "descriptor", (CallSiteAdvice) null);
  }
}
