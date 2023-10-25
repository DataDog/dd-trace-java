package com.datadog.iast.sensitive

import datadog.trace.test.util.DDSpecification

/**
 * Most of the testing is done via {@link com.datadog.iast.model.json.EvidenceRedactionTest}
 */
class SensitiveHandlerTest extends DDSpecification {

  void 'test that empty tokenizer returns nothing'() {
    given:
    final tokenizer = SensitiveHandler.Tokenizer.EMPTY

    when:
    final next = tokenizer.next()

    then:
    !next

    when:
    tokenizer.current()

    then:
    thrown(NoSuchElementException)
  }

  void 'test that current instance has a value'() {
    when:
    final current = SensitiveHandler.get()

    then:
    current != null
  }
}
