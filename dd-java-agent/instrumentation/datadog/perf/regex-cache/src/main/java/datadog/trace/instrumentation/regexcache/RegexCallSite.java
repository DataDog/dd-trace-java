package datadog.trace.instrumentation.regexcache;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.perf.PerfCallSites;
import datadog.trace.bootstrap.regex.RegexOps;
import java.util.regex.Pattern;

@CallSite(spi = PerfCallSites.class)
public class RegexCallSite {

  @CallSite.Around("java.util.regex.Pattern java.util.regex.Pattern.compile(java.lang.String)")
  public static Pattern aroundCompile(@CallSite.Argument(0) final String regex) {
    return RegexOps.compile(regex);
  }

  @CallSite.Around("java.util.regex.Pattern java.util.regex.Pattern.compile(java.lang.String, int)")
  public static Pattern aroundCompile(
      @CallSite.Argument(0) final String regex, @CallSite.Argument(1) final int flags) {
    return RegexOps.compile(regex, flags);
  }

  @CallSite.Around(
      "boolean java.util.regex.Pattern.matches(java.lang.String, java.lang.CharSequence)")
  public static boolean aroundPatternMatches(
      @CallSite.Argument(0) final String regex, @CallSite.Argument(1) final CharSequence input) {
    return RegexOps.patternMatches(regex, input);
  }

  @CallSite.Around("boolean java.lang.String.matches(java.lang.String)")
  public static boolean aroundMatches(
      @CallSite.This final String self, @CallSite.Argument(0) final String regex) {
    return RegexOps.matches(self, regex);
  }

  @CallSite.Around(
      "java.lang.String java.lang.String.replaceAll(java.lang.String, java.lang.String)")
  public static String aroundReplaceAll(
      @CallSite.This final String self,
      @CallSite.Argument(0) final String regex,
      @CallSite.Argument(1) final String replacement) {
    return RegexOps.replaceAll(self, regex, replacement);
  }

  @CallSite.Around(
      "java.lang.String java.lang.String.replaceFirst(java.lang.String, java.lang.String)")
  public static String aroundReplaceFirst(
      @CallSite.This final String self,
      @CallSite.Argument(0) final String regex,
      @CallSite.Argument(1) final String replacement) {
    return RegexOps.replaceFirst(self, regex, replacement);
  }

  @CallSite.Around("java.lang.String[] java.lang.String.split(java.lang.String)")
  public static String[] aroundSplit(
      @CallSite.This final String self, @CallSite.Argument(0) final String regex) {
    return RegexOps.split(self, regex);
  }

  @CallSite.Around("java.lang.String[] java.lang.String.split(java.lang.String, int)")
  public static String[] aroundSplit(
      @CallSite.This final String self,
      @CallSite.Argument(0) final String regex,
      @CallSite.Argument(1) final int limit) {
    return RegexOps.split(self, regex, limit);
  }
}
