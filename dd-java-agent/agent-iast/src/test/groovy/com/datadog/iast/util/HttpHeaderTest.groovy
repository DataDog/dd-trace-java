package com.datadog.iast.util

import com.datadog.iast.IastRequestContext
import spock.lang.Specification

import static com.datadog.iast.util.HttpHeader.Values.HEADERS

class HttpHeaderTest extends Specification {


  void 'test context headers'() {
    setup:
    final iastCtx = Spy(IastRequestContext)

    when:
    final parsed = HttpHeader.from(header.name)

    then:
    parsed != null

    when:
    final matches = parsed.matches(header.name.toUpperCase(Locale.ROOT))

    then:
    matches

    when:
    if (header instanceof HttpHeader.ContextAwareHeader) {
      header.onHeader(iastCtx, "my_value")
    }

    then:
    if (header instanceof HttpHeader.ContextAwareHeader) {
      1 * iastCtx._
    }

    where:
    header << HEADERS.values()
  }
}
