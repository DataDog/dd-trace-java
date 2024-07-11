package com.datadog.iast.model

import com.datadog.iast.model.json.VulnerabilityEncoding
import datadog.trace.api.iast.SourceTypes
import datadog.trace.test.util.DDSpecification
import groovy.json.JsonSlurper

import java.lang.ref.Reference
import java.lang.ref.WeakReference

import static datadog.trace.api.iast.VulnerabilityMarks.NOT_MARKED

class EvidenceTest extends DDSpecification {

  void 'test max size in the context'() {
    given:
    final maxSize = 8
    final context = new Evidence.Context(maxSize)

    when:
    final failed = (0..7).collect { context.put(it.toString(), null) }.count { false }

    then:
    failed == 0

    when:
    final newPut = context.put('8', null)

    then:
    !newPut

    when:
    final override = context.put('7', 'another')

    then:
    override
  }

  void 'test that evidences are consolidated preventing the GC from clearing data'() {
    given:
    final String name = "Hello world!"
    final Reference<?> ref = new WeakReference<>(name)

    when:
    final source = new Source(SourceTypes.REQUEST_PARAMETER_NAME, ref, "a random value")
    final batch = vulnBatchForSource(source)

    then:
    source.getName() == name
    final parsedBefore = new JsonSlurper().parseText(VulnerabilityEncoding.toJson(batch))
    parsedBefore["sources"][0]["name"] == name

    when:
    ref.clear()

    then:
    source.getName() == Source.GARBAGE_COLLECTED_REF
    final parsedAfter = new JsonSlurper().parseText(VulnerabilityEncoding.toJson(batch))
    parsedAfter["sources"][0]["name"] == name
  }

  private static VulnerabilityBatch vulnBatchForSource(final Source source) {
    final location = Location.forClassAndMethodAndLine(EvidenceTest.name, 'vulnBatchForSource', 69)
    final evidence = new Evidence("test", [new Range(0, 4, source, NOT_MARKED)] as Range[])
    final vuln = new Vulnerability(VulnerabilityType.INSECURE_COOKIE, location, evidence)
    final batch = new VulnerabilityBatch()
    batch.add(vuln)
    return batch
  }
}
