package datadog.trace.plugin.csi.samples;

import datadog.trace.agent.tooling.csi.CallSite;
import net.bytebuddy.asm.Advice;

@CallSite
public class AroundAdvice {

  @CallSite.Around(
      "java.lang.String java.lang.String.replaceAll(java.lang.String, java.lang.String)")
  public static String aroundStringReplaceAll(
      @Advice.This final String self,
      @Advice.Argument(0) final String regexp,
      @Advice.Argument(1) final String replacement) {
    return self.replaceAll(regexp, replacement);
  }
}
