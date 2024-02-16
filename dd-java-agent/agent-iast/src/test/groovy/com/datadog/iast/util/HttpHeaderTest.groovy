package com.datadog.iast.util

import spock.lang.Specification

class HttpHeaderTest extends Specification {

  void 'test that headers are not case sensitive'() {
    given:
    final name = header.name

    when:
    final sameName = HttpHeader.from(name)

    then:
    sameName == header

    when:
    final lowerHeader = HttpHeader.from(name.toLowerCase(Locale.ROOT))

    then:
    lowerHeader == header

    when:
    final upperHeader = HttpHeader.from(name.toUpperCase(Locale.ROOT))

    then:
    upperHeader == header

    when:
    final mixedChars = name.toCharArray()
    mixedChars.eachWithIndex { char entry, int i ->
      if (i % 2 == 0) {
        mixedChars[i] = Character.toLowerCase(entry)
      } else {
        mixedChars[i] = Character.toUpperCase(entry)
      }
    }
    final mixedHeader = HttpHeader.from(new String(mixedChars))

    then:
    mixedHeader == header

    when:
    final nonExisting = HttpHeader.from(name + '_')

    then:
    nonExisting == null

    where:
    header << HttpHeader.values()
  }

  void 'ensure headers can be used in the map'() {
    when:
    final matches = header.name  ==~ /[a-zA-Z0-9-]+/

    then:
    matches

    where:
    header << HttpHeader.values()
  }
}
