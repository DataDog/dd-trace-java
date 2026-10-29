package datadog.trace.instrumentation.regexcache

import static datadog.trace.api.config.IastConfig.IAST_ENABLED
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_EXPERIMENTAL_REGEX_CACHE_ENABLED

import foo.bar.RegexWorkload

class RegexCallSiteDisabledForkedTest extends RegexCallSiteSpecification {

  void 'does not rewrite JDK regex calls when the flag is off'() {
    when:
    final invokes = probeInvokes()

    then:
    invokes.containsAll(JDK_INVOKES)
    invokes.findAll { it.startsWith(CALL_SITE) }.isEmpty()
    !new PerfInstrumentation().isEnabled()
    RegexWorkload.run(3) == JdkRegexWorkload.run(3)
  }
}

class RegexCallSiteIastForkedTest extends RegexCallSiteSpecification {

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(TRACE_EXPERIMENTAL_REGEX_CACHE_ENABLED, 'true')
    injectSysConfig(IAST_ENABLED, 'true')
  }

  void 'does not rewrite JDK regex calls when IAST is fully enabled'() {
    when:
    final invokes = probeInvokes()

    then:
    invokes.containsAll(JDK_INVOKES)
    invokes.findAll { it.startsWith(CALL_SITE) }.isEmpty()
    !new PerfInstrumentation().isEnabled()
  }
}
