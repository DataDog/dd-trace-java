package datadog.trace.plugin.csi.samples;

import datadog.trace.agent.tooling.csi.CallSite;
import net.bytebuddy.asm.Advice;

@CallSite
public class BeforeAdvice {

  @CallSite.Before(
      "java.security.MessageDigest java.security.MessageDigest.getInstance(java.lang.String)")
  public static void beforeMessageDigestGetInstance(@Advice.Argument(0) final String algorithm) {}
}
