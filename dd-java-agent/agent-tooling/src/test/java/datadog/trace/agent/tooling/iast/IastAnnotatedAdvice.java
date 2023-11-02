package datadog.trace.agent.tooling.iast;

import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import net.bytebuddy.asm.Advice;

public class IastAnnotatedAdvice {
  @Advice.OnMethodExit
  @Sink(VulnerabilityTypes.SQL_INJECTION)
  static void exit() {}
}
