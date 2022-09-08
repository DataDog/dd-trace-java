package datadog.trace.plugin.csi.samples;

import datadog.trace.agent.tooling.csi.CallSite;
import java.net.URL;
import net.bytebuddy.asm.Advice;

@CallSite
public class AfterAdvice {

  @CallSite.After("void java.net.URL.<init>(java.lang.String)")
  public static URL afterUrlConstructor(
      @Advice.This final URL url, @Advice.Argument(0) final String spec) {
    return url;
  }
}
