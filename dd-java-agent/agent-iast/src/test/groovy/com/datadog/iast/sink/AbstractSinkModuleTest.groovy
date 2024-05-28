package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.model.Source
import com.datadog.iast.overhead.Operations
import com.datadog.iast.propagation.PropagationModuleImpl
import com.datadog.iast.taint.Ranges

import static com.datadog.iast.model.VulnerabilityType.SSRF
import static datadog.trace.api.iast.SourceTypes.REQUEST_PARAMETER_VALUE

class AbstractSinkModuleTest extends IastModuleImplTestBase {

  final StackTraceElement ignoredPackageClassElement = element("org.springframework.Ignored")
  final StackTraceElement notIgnoredPackageClassElement = element("datadog.smoketest.NotIgnored")
  final StackTraceElement notInIastExclusionTrie = element("not.in.iast.exclusion.Class")

  void 'filter ignored package element from stack'() {

    given:
    final StackTraceElement expected = notInIastExclusionTrie
    final list = Arrays.asList(ignoredPackageClassElement, expected, notIgnoredPackageClassElement)
    when:
    final StackTraceElement result = SinkModuleBase.findValidPackageForVulnerability(list.stream())
    then:
    result == expected
  }

  void 'not filter not ignored package element from stack'() {

    given:
    final StackTraceElement expected = notIgnoredPackageClassElement
    final list = Arrays.asList(ignoredPackageClassElement, expected, notInIastExclusionTrie)
    when:
    final StackTraceElement result = SinkModuleBase.findValidPackageForVulnerability(list.stream())
    then:
    result == expected
  }

  void 'If all elements are filtered returns the first one'() {

    given:
    final StackTraceElement expected = ignoredPackageClassElement
    final ignoredPackageClassElement2 = element("java.Ignored")
    final list = Arrays.asList(expected, ignoredPackageClassElement2)
    when:
    final StackTraceElement result = SinkModuleBase.findValidPackageForVulnerability(list.stream())
    then:
    result == expected
  }

  void 'test reporting evidence on objects'() {
    given:
    overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span) >> true
    final sink = new SinkModuleBase(dependencies) {}
    final propagation = new PropagationModuleImpl()
    final input = new String(source.value)
    ctx.getTaintedObjects().taint(input, Ranges.forCharSequence(input, source))

    when:
    propagation.taintObjectIfTainted(toReport, input)
    final evidence = sink.checkInjection(SSRF, toReport)

    then:
    evidence.ranges.length == 1
    final range = evidence.ranges[0]
    if (matches) {
      final taintedEvidence = evidence.value.substring(range.start, range.start + range.length)
      taintedEvidence == input
    } else {
      final taintedEvidence = evidence.value
      taintedEvidence != input
    }

    where:
    source                                                    | toReport                                  | matches
    new Source(REQUEST_PARAMETER_VALUE, 'url', 'datadog.com') | new URL('https://datadog.com/index.html') | true
    new Source(REQUEST_PARAMETER_VALUE, 'url', 'datadog.com') | new URI('https://datadog.com/index.html') | true
    new Source(REQUEST_PARAMETER_VALUE, 'url', 'datadog.com') | new URI('https://dAtAdOg.com/index.html') | false
    new Source(REQUEST_PARAMETER_VALUE, 'url', 'datadog.com') | new URI('https://dAtAdOg.com/index.html') | false
  }

  private StackTraceElement element(final String declaringClass) {
    return new StackTraceElement(declaringClass, "method", "fileName", 1)
  }
}
