package datadog.trace.instrumentation.csi.iast;

import datadog.trace.agent.tooling.csi.CallSite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@CallSite(spi = IastAdvice.class)
public class StringBuilderCallSite {

  private static final Logger LOG = LoggerFactory.getLogger(StringCallSite.class);

  @CallSite.After("void java.lang.StringBuilder.<init>(java.lang.String)")
  public static StringBuilder afterInit(
      @CallSite.This final StringBuilder self, @CallSite.Argument final String param) {
    LOG.debug("After init");
    return self;
  }

  @CallSite.After("void java.lang.StringBuilder.<init>(java.lang.CharSequence)")
  public static StringBuilder afterInit(
      @CallSite.This final StringBuilder self, @CallSite.Argument final CharSequence param) {
    LOG.debug("After init");
    return self;
  }

  @CallSite.After("java.lang.StringBuilder java.lang.StringBuilder.append(java.lang.CharSequence)")
  public static StringBuilder afterAppend(
      @CallSite.This final StringBuilder self,
      @CallSite.Argument final CharSequence param,
      @CallSite.Return final StringBuilder result) {
    LOG.debug("After append");
    return result;
  }

  @CallSite.After(
      "java.lang.StringBuilder java.lang.StringBuilder.append(java.lang.CharSequence, int, int)")
  public static StringBuilder afterAppend(
      @CallSite.This final StringBuilder self,
      @CallSite.Argument final CharSequence param,
      @CallSite.Argument final int start,
      @CallSite.Argument final int end,
      @CallSite.Return final StringBuilder result) {
    LOG.debug("After append");
    return result;
  }

  @CallSite.After("java.lang.StringBuilder java.lang.StringBuilder.append(java.lang.String)")
  public static StringBuilder afterAppend(
      @CallSite.This final StringBuilder self,
      @CallSite.Argument final String param,
      @CallSite.Return final StringBuilder result) {
    LOG.debug("After append");
    return result;
  }

  @CallSite.Around(
      "java.lang.StringBuilder java.lang.StringBuilder.replace(int, int, java.lang.String)")
  public static StringBuilder aroundReplace(
      @CallSite.This final StringBuilder self,
      @CallSite.Argument final int start,
      @CallSite.Argument final int end,
      @CallSite.Argument final String param) {
    LOG.debug("Around replace");
    return self.replace(start, end, param);
  }

  @CallSite.After("java.lang.StringBuilder java.lang.StringBuilder.reverse()")
  public static StringBuilder afterReverse(
      @CallSite.This final StringBuilder self, @CallSite.Return final StringBuilder result) {
    LOG.debug("After reverse");
    return result;
  }

  @CallSite.After("java.lang.String java.lang.StringBuilder.toString()")
  public static String afterToString(
      @CallSite.This final StringBuilder self, @CallSite.Return final String result) {
    LOG.debug("After toString");
    return result;
  }
}
