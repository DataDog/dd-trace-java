package datadog.trace.instrumentation.csi.iast;

import datadog.trace.agent.tooling.csi.CallSite;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@CallSite(spi = IastAdvice.class)
public class StringCallSite {

  private static final Logger LOG = LoggerFactory.getLogger(StringCallSite.class);

  @CallSite.After("void java.lang.String.<init>(java.lang.String)")
  public static String afterInit(
      @CallSite.This final String self, @CallSite.Argument final String param) {
    LOG.debug("After init");
    return self;
  }

  @CallSite.After("void java.lang.String.<init>(java.lang.StringBuilder)")
  public static String afterInit(
      @CallSite.This final String self, @CallSite.Argument final StringBuilder param) {
    LOG.debug("After init");
    return self;
  }

  @CallSite.After("java.lang.String java.lang.String.concat(java.lang.String)")
  public static String afterConcat(
      @CallSite.This final String self,
      @CallSite.Argument final String param,
      @CallSite.Return final String result) {
    LOG.debug("After concat");
    return result;
  }

  @CallSite.Around(
      "java.lang.String java.lang.String.replaceAll(java.lang.String, java.lang.String)")
  public static String aroundReplaceAll(
      @CallSite.This final String self,
      @CallSite.Argument final String regexp,
      @CallSite.Argument final String replacement) {
    LOG.debug("Around replaceAll");
    return self.replaceAll(regexp, replacement);
  }

  @CallSite.Around(
      "java.lang.String java.lang.String.replaceFirst(java.lang.String, java.lang.String)")
  public static String aroundReplaceFirst(
      @CallSite.This final String self,
      @CallSite.Argument final String regexp,
      @CallSite.Argument final String replacement) {
    LOG.debug("Around replaceFirst");
    return self.replaceFirst(regexp, replacement);
  }

  @CallSite.Around(
      "java.lang.String java.lang.String.replace(java.lang.CharSequence, java.lang.CharSequence)")
  public static String aroundReplace(
      @CallSite.This final String self,
      @CallSite.Argument final CharSequence target,
      @CallSite.Argument final CharSequence replacement) {
    LOG.debug("Around replace");
    return self.replace(target, replacement);
  }

  @CallSite.Around("java.lang.String[] java.lang.String.split(java.lang.String)")
  public static String[] aroundSplit(
      @CallSite.This final String self, @CallSite.Argument final String regexp) {
    LOG.debug("Around split");
    return self.split(regexp);
  }

  @CallSite.Around("java.lang.String[] java.lang.String.split(java.lang.String, int)")
  public static String[] aroundSplit(
      @CallSite.This final String self,
      @CallSite.Argument final String regexp,
      @CallSite.Argument final int limit) {
    LOG.debug("Around split");
    return self.split(regexp, limit);
  }

  @CallSite.After("java.lang.CharSequence java.lang.String.subSequence(int, int)")
  public static CharSequence afterSubSequence(
      @CallSite.This final String self,
      @CallSite.Argument final int beginIndex,
      @CallSite.Argument final int endIndex,
      @CallSite.Return final CharSequence result) {
    LOG.debug("After subSequence");
    return result;
  }

  @CallSite.After("java.lang.String java.lang.String.substring(int)")
  public static String afterSubstring(
      @CallSite.This final String self,
      @CallSite.Argument final int beginIndex,
      @CallSite.Return final String result) {
    LOG.debug("After substring");
    return result;
  }

  @CallSite.After("java.lang.String java.lang.String.substring(int, int)")
  public static String afterSubstring(
      @CallSite.This final String self,
      @CallSite.Argument final int beginIndex,
      @CallSite.Argument final int endIndex,
      @CallSite.Return final String result) {
    LOG.debug("After substring");
    return result;
  }

  @CallSite.After("java.lang.String java.lang.String.toLowerCase()")
  public static String afterToLowerCase(
      @CallSite.This final String self, @CallSite.Return final String result) {
    LOG.debug("After toLowerCase");
    return result;
  }

  @CallSite.After("java.lang.String java.lang.String.toLowerCase(java.util.Locale)")
  public static String afterToLowerCase(
      @CallSite.This final String self,
      @CallSite.Argument final Locale locale,
      @CallSite.Return final String result) {
    LOG.debug("After toLowerCase");
    return result;
  }

  @CallSite.After("java.lang.String java.lang.String.toUpperCase()")
  public static String afterToUpperCase(
      @CallSite.This final String self, @CallSite.Return final String result) {
    LOG.debug("After toUpperCase");
    return result;
  }

  @CallSite.After("java.lang.String java.lang.String.toUpperCase(java.util.Locale)")
  public static String afterToUpperCase(
      @CallSite.This final String self,
      @CallSite.Argument final Locale locale,
      @CallSite.Return final String result) {
    LOG.debug("After toUpperCase");
    return result;
  }

  @CallSite.After("java.lang.String java.lang.String.trim()")
  public static String afterTrim(
      @CallSite.This final String self, @CallSite.Return final String result) {
    LOG.debug("After trim");
    return result;
  }

  @CallSite.After("java.lang.String java.lang.String.toString()")
  public static String afterToString(
      @CallSite.This final String self, @CallSite.Return final String result) {
    LOG.debug("After toString");
    return result;
  }
}
