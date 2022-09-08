package datadog.trace.plugin.csi.samples;

import datadog.trace.agent.tooling.csi.CallSite;
import net.bytebuddy.asm.Advice;

@CallSite(helpers = {HelpersAdvice.SampleHelper1.class, HelpersAdvice.SampleHelper2.class})
public class HelpersAdvice {

  @CallSite.Before(
      "java.security.MessageDigest java.security.MessageDigest.getInstance(java.lang.String)")
  public static void beforeMessageDigestGetInstance(@Advice.Argument(0) final String algorithm) {}

  public static class SampleHelper1 {}

  public static class SampleHelper2 {}
}
