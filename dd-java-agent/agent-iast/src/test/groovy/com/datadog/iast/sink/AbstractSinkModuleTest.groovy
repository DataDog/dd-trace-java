package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase

class AbstractSinkModuleTest  extends IastModuleImplTestBase {

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

  private StackTraceElement element(final String declaringClass) {
    return new StackTraceElement(declaringClass, "method", "fileName", 1)
  }
}
