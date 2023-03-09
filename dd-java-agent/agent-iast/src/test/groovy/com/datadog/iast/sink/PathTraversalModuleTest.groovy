package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.IastRequestContext
import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityType
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.sink.PathTraversalModule
import datadog.trace.bootstrap.instrumentation.api.AgentSpan

import static com.datadog.iast.taint.TaintUtils.addFromTaintFormat
import static com.datadog.iast.taint.TaintUtils.fromTaintFormat
import static com.datadog.iast.taint.TaintUtils.getStringFromTaintFormat
import static com.datadog.iast.taint.TaintUtils.taintFormat

class PathTraversalModuleTest extends IastModuleImplTestBase {

  private static final char SP = File.separatorChar

  private PathTraversalModule module

  private List<Object> objectHolder

  private IastRequestContext ctx

  def setup() {
    module = registerDependencies(new PathTraversalModuleImpl())
    objectHolder = []
    ctx = new IastRequestContext()
    final reqCtx = Mock(RequestContext) {
      getData(RequestContextSlot.IAST) >> ctx
    }
    final span = Mock(AgentSpan) {
      getSpanId() >> 123456
      getRequestContext() >> reqCtx
    }
    tracer.activeSpan() >> span
    overheadController.consumeQuota(_, _) >> true
  }

  void 'iast module detects path traversal with String path (#path)'(final String path, final String expected) {
    setup:
    final param = mapTainted(path)

    when:
    module.onPathTraversal(param)

    then:
    if (expected != null) {
      1 * reporter.report(_, _) >> { args -> assertVulnerability(args[1] as Vulnerability, expected) }
    } else {
      0 * reporter.report(_, _)
    }

    where:
    path         | expected
    '/var'       | null
    '/==>var<==' | "/==>var<=="
  }

  void 'iast module detects path traversal with URI (#path)'(final String path, final String expected) {
    setup:
    final param = mapTaintedURI(path)

    when:
    module.onPathTraversal(param)

    then:
    if (expected != null) {
      1 * reporter.report(_, _) >> { args -> assertVulnerability(args[1] as Vulnerability, expected) }
    } else {
      0 * reporter.report(_, _)
    }

    where:
    path         | expected
    '/var'       | null
    '/==>var<==' | "/==>var<=="
  }

  void 'iast module detects path traversal with String (#first, #rest)'() {
    setup:
    final parent = mapTainted(first)
    final children = mapTainted(rest)

    when:
    module.onPathTraversal((String) parent, children)

    then:
    if (expected != null) {
      1 * reporter.report(_, _) >> { args -> assertVulnerability(args[1] as Vulnerability, expected) }
    } else {
      0 * reporter.report(_, _)
    }

    where:
    first        | rest        | expected
    '/var'       | 'log'       | null
    null         | 'log'       | null
    '/var'       | '==>log<==' | "/var${SP}==>log<=="
    '/==>var<==' | '==>log<==' | "/==>var<==${SP}==>log<=="
    '/==>var<==' | 'log'       | "/==>var<==${SP}log"
  }

  void 'iast module detects path traversal with String (#first, #rest)'(final String first, final String[] rest, final String expected) {
    setup:
    final parent = mapTainted(first)
    final children = mapTaintedArray(rest)

    when:
    module.onPathTraversal(parent, children)

    then:
    if (expected != null) {
      1 * reporter.report(_, _) >> { args -> assertVulnerability(args[1] as Vulnerability, expected) }
    } else {
      0 * reporter.report(_, _)
    }

    where:
    first        | rest                                        | expected
    '/var'       | [] as String[]                              | null
    '/var'       | ['log', 'log.log'] as String[]              | null
    '/var'       | ['==>log<==', null, 'test.log'] as String[] | "/var${SP}==>log<==${SP}test.log"
    '/var'       | ['==>log<==', 'test.log'] as String[]       | "/var${SP}==>log<==${SP}test.log"
    '/==>var<==' | ['==>log<==', 'test.log'] as String[]       | "/==>var<==${SP}==>log<==${SP}test.log"
    '/==>var<==' | ['==>log<==', '==>test<==.log'] as String[] | "/==>var<==${SP}==>log<==${SP}==>test<==.log"
  }

  void 'iast module detects path traversal with File (#first, #rest)'(final File first, final String rest, final String expected) {
    setup:
    final children = mapTainted(rest)

    when:
    module.onPathTraversal((File) first, children)

    then:
    if (expected != null) {
      1 * reporter.report(_, _) >> { args -> assertVulnerability(args[1] as Vulnerability, expected) }
    } else {
      0 * reporter.report(_, _)
    }

    where:
    first        | rest            | expected
    null         | 'log'           | null
    file('/var') | 'log'           | null
    null         | '==>log<==.txt' | "==>log<==.txt"
    file('/var') | '==>log<==.txt' | "${SP}var${SP}==>log<==.txt"
  }

  private String[] mapTaintedArray(final String... items) {
    return items.collect(this.&mapTainted) as String[]
  }

  private String mapTainted(final String value) {
    final result = addFromTaintFormat(ctx.taintedObjects, value)
    objectHolder.add(result)
    return result
  }

  private URI mapTaintedURI(final String s) {
    final ranges = fromTaintFormat(s)
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
