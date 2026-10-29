package datadog.trace.instrumentation.regexcache

import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_EXPERIMENTAL_REGEX_CACHE_ENABLED

import datadog.trace.agent.tooling.InstrumenterModule.TargetSystem
import datadog.trace.bootstrap.regex.RegexCache
import foo.bar.RegexBytecodeProbe
import foo.bar.RegexWorkload
import java.util.regex.Pattern

class RegexCallSiteTest extends RegexCallSiteSpecification {

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(TRACE_EXPERIMENTAL_REGEX_CACHE_ENABLED, 'true')
  }

  void 'rewrites every JDK regex call in application classes'() {
    when:
    final invokes = probeInvokes()

    then:
    invokes.containsAll(CALL_SITE_INVOKES)
    invokes.intersect(JDK_INVOKES).isEmpty()

    when:
    final results = RegexBytecodeProbe.callAll()

    then:
    results.length == 8
    (results[0] as Pattern).pattern() == 'a+'
    (results[1] as Pattern).flags() == Pattern.CASE_INSENSITIVE
    results[6] == ['a', 'b'] as String[]
    results[7] == ['a,b'] as String[]
  }

  void 'workload returns the same results as the JDK'() {
    expect:
    BytecodeCapture.invokesAfterTransform(INSTRUMENTATION, RegexWorkload.name, getClass().classLoader)
      .containsAll(CALL_SITE_INVOKES)
    RegexWorkload.run(100) == JdkRegexWorkload.run(100)
  }

  void 'rewritten #method behaves like the JDK for #args'() {
    when:
    final rewritten = outcome { RegexWorkload."$method"(*args) }
    final jdk = outcome { JdkRegexWorkload."$method"(*args) }

    then:
    rewritten == jdk

    where:
    method           | args
    'compile'        | ['a+b']
    'compile'        | ['[']
    'compile'        | ['a+b', Pattern.CASE_INSENSITIVE]
    'compile'        | ['a+b', Integer.MIN_VALUE]
    'patternMatches' | ['[0-9]+', '123']
    'patternMatches' | ['[0-9]+', new StringBuilder('12a')]
    'patternMatches' | ['(', 'x']
    'matches'        | ['hello', 'h.*o']
    'matches'        | ['hello', '*']
    'replaceAll'     | ['a1b22', '[0-9]+', '#']
    'replaceAll'     | ['a1b22', '([0-9])', '<$1>']
    'replaceAll'     | ['a1b22', '[0-9]', '$9']
    'replaceAll'     | ['a1b22', '[0-9]', null]
    'replaceFirst'   | ['a1b22', '[0-9]+', '#']
    'replaceFirst'   | ['a1b22', '[', '#']
    'split'          | ['a,b,,c,,', ',']
    'split'          | ['a1b22c', '[0-9]+']
    'split'          | ['a.b', '.']
    'split'          | ['', ',']
    'split'          | ['a|b', '\\|']
    'split'          | ['a,b,,c,,', ',', -1]
    'split'          | ['a1b22c', '[0-9]+', 2]
    'split'          | ['a,b', '[', 0]
  }

  void 'repeated literal regexes are served from the cache'() {
    given:
    final hitsBefore = RegexCache.hits()

    when:
    RegexWorkload.run(100)

    then:
    RegexCache.hits() > hitsBefore
  }

  void 'is enabled when the flag is on'() {
    expect:
    new PerfInstrumentation().isEnabled()
  }

  void 'applies to the tracing target system: #systems'() {
    expect:
    new PerfInstrumentation().isApplicable(systems as Set) == expected

    where:
    systems                                     | expected
    [TargetSystem.TRACING]                      | true
    [TargetSystem.TRACING, TargetSystem.APPSEC] | true
    [TargetSystem.PROFILING]                    | false
    []                                          | false
  }

  void 'excludes JDK and agent callers: #className'() {
    expect:
    PerfInstrumentation.isExcluded(className) == excluded

    where:
    className                                     | excluded
    'java.lang.String'                            | true
    'javax.servlet.Filter'                        | true
    'jdk.internal.Foo'                            | true
    'sun.misc.Unsafe'                             | true
    'com.sun.net.httpserver.HttpServer'           | true
    'datadog.trace.core.CoreTracer'               | true
    'datadog.okhttp3.OkHttpClient'                | true
    'com.datadog.profiling.Foo'                   | true
    'foo.bar.RegexWorkload'                       | false
    'org.springframework.util.StringUtils'        | false
    'javalin.Javalin'                             | false
  }

  private static List<Object> outcome(final Closure<?> call) {
    try {
      final result = call.call()
      return ['ok', result instanceof Object[] ? (result as List) : result]
    } catch (final Throwable e) {
      return ['error', e.class, e.message]
    }
  }
}
