package datadog.trace.plugin.csi.samples;

import datadog.trace.agent.tooling.csi.CallSite;
import java.net.URL;
import net.bytebuddy.asm.Advice;

@CallSite
public class StacksSampleAdvice {

  @CallSite.Around("java.lang.String java.lang.String.concat(java.lang.String)")
  public static String around(
      @Advice.This final String self, @Advice.Argument(0) final String param) {
    return self.concat(param);
  }

  @CallSite.Before(
      "void java.net.URL.<init>(java.lang.String, java.lang.String, int, java.lang.String)")
  public static void beforeConstructor(
      @Advice.Argument(0) final String protocol,
      @Advice.Argument(1) final String host,
      @Advice.Argument(2) final int port,
      @Advice.Argument(3) final String file) {}

  @CallSite.After(
      "void java.net.URL.<init>(java.lang.String, java.lang.String, int, java.lang.String)")
  public static URL afterConstructor(
      @Advice.This final URL url,
      @Advice.Argument(0) final String protocol,
      @Advice.Argument(1) final String host,
      @Advice.Argument(2) final int port,
      @Advice.Argument(3) final String file) {
    return url;
  }

  @CallSite.Before(
      "void java.net.URL.<init>(java.lang.String, java.lang.String, int, java.lang.String)")
  public static void beforeConstructorEmpty() {}

  @CallSite.After(
      "void java.net.URL.<init>(java.lang.String, java.lang.String, int, java.lang.String)")
  public static URL afterConstructorEmpty(@Advice.This final URL url) {
    return url;
  }

  @CallSite.Before("java.lang.StringBuilder java.lang.StringBuilder.insert(int, long)")
  public static void beforeLong(
      @Advice.This final StringBuilder self,
      @Advice.Argument(0) final int offset,
      @Advice.Argument(1) final long value) {}

  @CallSite.After("java.lang.StringBuilder java.lang.StringBuilder.insert(int, long)")
  public static StringBuilder afterLong(
      @Advice.Argument(0) final int offset,
      @Advice.Argument(1) final long value,
      @Advice.Return final StringBuilder result) {
    return result;
  }
}
