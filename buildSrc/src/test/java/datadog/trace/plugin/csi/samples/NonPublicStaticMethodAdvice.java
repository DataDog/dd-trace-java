package datadog.trace.plugin.csi.samples;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.agent.tooling.csi.CallSite.Before;

@CallSite
public class NonPublicStaticMethodAdvice {

  @Before("java.lang.String java.lang.String.concat(java.lang.String)")
  private void before() {}
}
