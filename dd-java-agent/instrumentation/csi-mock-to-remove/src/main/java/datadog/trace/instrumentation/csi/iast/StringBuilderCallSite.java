package datadog.trace.instrumentation.csi.iast;

import datadog.trace.agent.tooling.csi.CallSite;
import net.bytebuddy.asm.Advice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@CallSite(spi = IastAdvice.class)
public class StringBuilderCallSite {

  private static final Logger LOG = LoggerFactory.getLogger(StringCallSite.class);

  @CallSite.After("void java.lang.StringBuilder.<init>(java.lang.String)")
  public static StringBuilder afterInit(
      @Advice.This final StringBuilder self, @Advice.Argument(0) final String param) {
    LOG.debug("After init");
    return self;
  }

  @CallSite.After("void java.lang.StringBuilder.<init>(java.lang.CharSequence)")
  public static StringBuilder afterInit(
      @Advice.This final StringBuilder self, @Advice.Argument(0) final CharSequence param) {
    LOG.debug("After init");
    return self;
  }

  @CallSite.After("java.lang.StringBuilder java.lang.StringBuilder.append(java.lang.CharSequence)")
  public static StringBuilder afterAppend(
      @Advice.Argument(0) final CharSequence param, @Advice.Return final StringBuilder result) {
    LOG.debug("After append");
    return result;
  }

  @CallSite.After(
      "java.lang.StringBuilder java.lang.StringBuilder.append(java.lang.CharSequence, int, int)")
  public static StringBuilder afterAppend(
      @Advice.This final StringBuilder self,
      @Advice.Argument(0) final CharSequence param,
      @Advice.Argument(1) final int start,
      @Advice.Argument(2) final int end,
      @Advice.Return final StringBuilder result) {
    LOG.debug("After append");
    return result;
  }

  /**
   * This combination is not possible using stack manipuation, that's why we use previous variant
   * with @Advice.This
   */
  /*
  @CallSite.After(
      "java.lang.StringBuilder java.lang.StringBuilder.append(java.lang.CharSequence, int, int)")
  public static StringBuilder afterAppend(
      @Advice.Argument(0) final CharSequence param,
      @Advice.Argument(1) final int start,
      @Advice.Argument(2) final int end,
      @Advice.Return final StringBuilder result) {
    LOG.debug("After append");
    return result;
  }
   */

  @CallSite.After("java.lang.StringBuilder java.lang.StringBuilder.append(java.lang.String)")
  public static StringBuilder afterAppend(
      @Advice.Argument(0) final String param, @Advice.Return final StringBuilder result) {
    LOG.debug("After append");
    return result;
  }

  @CallSite.Around(
      "java.lang.StringBuilder java.lang.StringBuilder.replace(int, int, java.lang.String)")
  public static StringBuilder aroundReplace(
      @Advice.This final StringBuilder self,
      @Advice.Argument(0) final int start,
      @Advice.Argument(1) final int end,
      @Advice.Argument(2) final String param) {
    LOG.debug("Around replace");
    return self.replace(start, end, param);
  }

  @CallSite.After("java.lang.StringBuilder java.lang.StringBuilder.reverse()")
  public static StringBuilder afterReverse(@Advice.Return final StringBuilder result) {
    LOG.debug("After reverse");
    return result;
  }

  @CallSite.After("java.lang.String java.lang.StringBuilder.toString()")
  public static String afterToString(
      @Advice.This final StringBuilder self, @Advice.Return final String result) {
    LOG.debug("After toString");
    return result;
  }
}
