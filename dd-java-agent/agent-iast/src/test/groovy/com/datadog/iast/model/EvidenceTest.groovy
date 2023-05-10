package com.datadog.iast.model

import datadog.trace.test.util.DDSpecification

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
}
