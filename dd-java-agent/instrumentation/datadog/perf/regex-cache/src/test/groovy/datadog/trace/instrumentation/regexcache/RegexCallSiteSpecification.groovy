package datadog.trace.instrumentation.regexcache

import datadog.trace.agent.test.InstrumentationSpecification

abstract class RegexCallSiteSpecification extends InstrumentationSpecification {

  static final String PROBE = 'foo.bar.RegexBytecodeProbe'

  static final String CALL_SITE = 'datadog/trace/instrumentation/regexcache/RegexCallSite'

  static final List<String> JDK_INVOKES = [
    'java/util/regex/Pattern.compile (Ljava/lang/String;)Ljava/util/regex/Pattern;',
    'java/util/regex/Pattern.compile (Ljava/lang/String;I)Ljava/util/regex/Pattern;',
    'java/util/regex/Pattern.matches (Ljava/lang/String;Ljava/lang/CharSequence;)Z',
    'java/lang/String.matches (Ljava/lang/String;)Z',
    'java/lang/String.replaceAll (Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;',
    'java/lang/String.replaceFirst (Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;',
    'java/lang/String.split (Ljava/lang/String;)[Ljava/lang/String;',
    'java/lang/String.split (Ljava/lang/String;I)[Ljava/lang/String;',
  ]

  static final List<String> CALL_SITE_INVOKES = [
    "${CALL_SITE}.aroundCompile (Ljava/lang/String;)Ljava/util/regex/Pattern;",
    "${CALL_SITE}.aroundCompile (Ljava/lang/String;I)Ljava/util/regex/Pattern;",
    "${CALL_SITE}.aroundPatternMatches (Ljava/lang/String;Ljava/lang/CharSequence;)Z",
    "${CALL_SITE}.aroundMatches (Ljava/lang/String;Ljava/lang/String;)Z",
    "${CALL_SITE}.aroundReplaceAll (Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
    "${CALL_SITE}.aroundReplaceFirst (Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
    "${CALL_SITE}.aroundSplit (Ljava/lang/String;Ljava/lang/String;)[Ljava/lang/String;",
    "${CALL_SITE}.aroundSplit (Ljava/lang/String;Ljava/lang/String;I)[Ljava/lang/String;",
  ]*.toString()

  List<String> probeInvokes() {
    return BytecodeCapture.invokesAfterTransform(INSTRUMENTATION, PROBE, getClass().classLoader)
  }
}
