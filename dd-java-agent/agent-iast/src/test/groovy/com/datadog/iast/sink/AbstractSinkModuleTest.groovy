package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.model.Source
import com.datadog.iast.overhead.Operations
import com.datadog.iast.propagation.PropagationModuleImpl
import com.datadog.iast.taint.Ranges
import datadog.trace.api.iast.Taintable

import java.lang.ref.WeakReference

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

  void 'test reporting with taintables'() {
    setup:
    final sink = new SinkModuleBase(dependencies) {}
    final value = 'datadog.com'
    final valueRef = new WeakReference<>(value)
    final source = new Source(REQUEST_PARAMETER_VALUE, 'url', valueRef)

    and:
    final taintable = new MockTaintable(source: source)

    when: 'original source value is not tainted'
    def evidence = sink.checkInjection(SSRF, taintable)

    then: 'a fallback evidence is provided'
    evidence.value == "Tainted reference detected in " + taintable.class
    evidence.ranges.length == 1
    evidence.ranges[0].start == 0
    evidence.ranges[0].length == evidence.value.length()

    when: 'original source value is tainted'
    ctx.getTaintedObjects().taint(value, Ranges.forCharSequence(value, source))
    evidence = sink.checkInjection(SSRF, taintable)

    then: 'the proper value is set in the evidence'
    evidence.value == value
    evidence.ranges.length == 1
    evidence.ranges[0].start == 0
    evidence.ranges[0].length == value.length()

    when: 'original source cleared by the GC'
    valueRef.clear()
    evidence = sink.checkInjection(SSRF, taintable)

    then: 'a fallback evidence is provided'
    evidence.value == "Tainted reference detected in " + taintable.class
    evidence.ranges.length == 1
    evidence.ranges[0].start == 0
    evidence.ranges[0].length == evidence.value.length()
  }

  private StackTraceElement element(final String declaringClass) {
    return new StackTraceElement(declaringClass, "method", "fileName", 1)
  }

  private static class MockTaintable implements Taintable {
    private Source source

    @SuppressWarnings('CodeNarc')
    @Override
    Source $$DD$getSource() {
      return source
    }

    @SuppressWarnings('CodeNarc')
    @Override
    void $$DD$setSource(Source source) {
      this.source = source
    }
  }
}
