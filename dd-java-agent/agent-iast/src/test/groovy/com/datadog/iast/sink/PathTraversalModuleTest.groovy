package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.Reporter
import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityType
import datadog.trace.api.iast.VulnerabilityMarks
import datadog.trace.api.iast.sink.PathTraversalModule

import static com.datadog.iast.taint.TaintUtils.addFromTaintFormat
import static com.datadog.iast.taint.TaintUtils.fromTaintFormat
import static com.datadog.iast.taint.TaintUtils.getStringFromTaintFormat
import static com.datadog.iast.taint.TaintUtils.taintFormat
import static datadog.trace.api.iast.VulnerabilityMarks.NOT_MARKED

class PathTraversalModuleTest extends IastModuleImplTestBase {

  private static final char SP = File.separatorChar

  private PathTraversalModule module

  def setup() {
    module = new PathTraversalModuleImpl(dependencies)
  }

  @Override
  protected Reporter buildReporter() {
    return Mock(Reporter)
  }

  void 'iast module detects path traversal with String path (#path)'(final String path, final int mark, final String expected) {
    setup:
    final param = mapTainted(path, mark)

    when:
    module.onPathTraversal(param)

    then:
    if (expected != null) {
      1 * reporter.report(_, _) >> { args -> assertVulnerability(args[1] as Vulnerability, expected) }
    } else {
      0 * reporter.report(_, _)
    }

    where:
    path         | mark                                   | expected
    '/var'       | NOT_MARKED                             | null
    '/==>var<==' | NOT_MARKED                             | "/==>var<=="
    '/==>var<==' | VulnerabilityMarks.PATH_TRAVERSAL_MARK | null
    '/==>var<==' | VulnerabilityMarks.SQL_INJECTION_MARK  | "/==>var<=="
  }

  void 'iast module detects path traversal with URI (#path)'(final String path, final int mark, final String expected) {
    setup:
    final param = mapTaintedURI(path, mark)

    when:
    module.onPathTraversal(param)

    then:
    if (expected != null) {
      1 * reporter.report(_, _) >> { args -> assertVulnerability(args[1] as Vulnerability, expected) }
    } else {
      0 * reporter.report(_, _)
    }

    where:
    path         | mark                                   | expected
    '/var'       | NOT_MARKED                             | null
    '/==>var<==' | NOT_MARKED                             | "/==>var<=="
    '/==>var<==' | VulnerabilityMarks.PATH_TRAVERSAL_MARK | null
    '/==>var<==' | VulnerabilityMarks.SQL_INJECTION_MARK  | "/==>var<=="
  }

  void 'iast module detects path traversal with String (#first, #rest, #mark)'() {
    setup:
    final parent = mapTainted(first, mark)
    final children = mapTainted(rest, mark)

    when:
    module.onPathTraversal((String) parent, children)

    then:
    if (expected != null) {
      1 * reporter.report(_, _) >> { args -> assertVulnerability(args[1] as Vulnerability, expected) }
    } else {
      0 * reporter.report(_, _)
    }

    where:
    first        | rest        | mark                                   | expected
    '/var'       | 'log'       | NOT_MARKED                             | null
    null         | 'log'       | NOT_MARKED                             | null
    '/var'       | '==>log<==' | NOT_MARKED                             | "/var${SP}==>log<=="
    '/==>var<==' | '==>log<==' | NOT_MARKED                             | "/==>var<==${SP}==>log<=="
    '/==>var<==' | 'log'       | NOT_MARKED                             | "/==>var<==${SP}log"
    '/==>var<==' | '==>log<==' | VulnerabilityMarks.PATH_TRAVERSAL_MARK | null
    '/==>var<==' | '==>log<==' | VulnerabilityMarks.SQL_INJECTION_MARK  | "/==>var<==${SP}==>log<=="
  }

  void 'iast module detects path traversal with String (#first, #rest)'(final String first, final String[] rest, final int mark, final String expected) {
    setup:
    final parent = mapTainted(first, mark)
    final children = mapTaintedArray(mark, rest)

    when:
    module.onPathTraversal(parent, children)

    then:
    if (expected != null) {
      1 * reporter.report(_, _) >> { args -> assertVulnerability(args[1] as Vulnerability, expected) }
    } else {
      0 * reporter.report(_, _)
    }

    where:
    first        | rest                                        | mark                                   | expected
    '/var'       | [] as String[]                              | NOT_MARKED                             | null
    '/var'       | ['log', 'log.log'] as String[]              | NOT_MARKED                             | null
    '/var'       | ['==>log<==', null, 'test.log'] as String[] | NOT_MARKED                             | "/var${SP}==>log<==${SP}test.log"
    '/var'       | ['==>log<==', 'test.log'] as String[]       | NOT_MARKED                             | "/var${SP}==>log<==${SP}test.log"
    '/==>var<==' | ['==>log<==', 'test.log'] as String[]       | NOT_MARKED                             | "/==>var<==${SP}==>log<==${SP}test.log"
    '/==>var<==' | ['==>log<==', '==>test<==.log'] as String[] | NOT_MARKED                             | "/==>var<==${SP}==>log<==${SP}==>test<==.log"
    '/==>var<==' | ['==>log<==', '==>test<==.log'] as String[] | VulnerabilityMarks.PATH_TRAVERSAL_MARK | null
    '/==>var<==' | ['==>log<==', '==>test<==.log'] as String[] | VulnerabilityMarks.SQL_INJECTION_MARK  | "/==>var<==${SP}==>log<==${SP}==>test<==.log"
  }

  void 'iast module detects path traversal with File (#first, #rest)'(final File first, final String rest, final int mark, final String expected) {
    setup:
    final children = mapTainted(rest, mark)

    when:
    module.onPathTraversal((File) first, children)

    then:
    if (expected != null) {
      1 * reporter.report(_, _) >> { args -> assertVulnerability(args[1] as Vulnerability, expected) }
    } else {
      0 * reporter.report(_, _)
    }

    where:
    first        | rest            | mark                                   | expected
    null         | 'log'           | NOT_MARKED                             | null
    file('/var') | 'log'           | NOT_MARKED                             | null
    null         | '==>log<==.txt' | NOT_MARKED                             | "==>log<==.txt"
    file('/var') | '==>log<==.txt' | NOT_MARKED                             | "${SP}var${SP}==>log<==.txt"
    file('/var') | '==>log<==.txt' | VulnerabilityMarks.PATH_TRAVERSAL_MARK | null
    file('/var') | '==>log<==.txt' | VulnerabilityMarks.SQL_INJECTION_MARK  | "${SP}var${SP}==>log<==.txt"
  }

  private String[] mapTaintedArray(final int mark, final String... items) {
    return items.collect { item -> return mapTainted(item, mark) } as String[]
  }

  private String mapTainted(final String value, int mark) {
    final result = addFromTaintFormat(ctx.taintedObjects, value, mark)
    objectHolder.add(result)
    return result
  }

  private URI mapTaintedURI(final String s, final int mark) {
    final ranges = fromTaintFormat(s, mark)
    if (ranges == null || ranges.length == 0) {
      return new URI(s)
    }
    final result = new URI(getStringFromTaintFormat(s))
    ctx.taintedObjects.taint(result, ranges)
    objectHolder.add(result)
    return result
  }

  private static void assertVulnerability(final Vulnerability vuln, final String expected) {
    assert vuln != null
    assert vuln.getType() == VulnerabilityType.PATH_TRAVERSAL
    assert vuln.getLocation() != null
    final evidence = vuln.getEvidence()
    assert evidence != null
    final formatted = taintFormat(evidence.getValue(), evidence.getRanges())
    assert formatted == expected
  }

  private static File file(final String value) {
    return new File(value)
  }
}
