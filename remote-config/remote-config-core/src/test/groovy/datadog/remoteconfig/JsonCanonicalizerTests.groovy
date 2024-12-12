package datadog.remoteconfig

import spock.lang.Specification

import java.nio.charset.StandardCharsets

class JsonCanonicalizerTests extends Specification {
  void 'map keys are reordered'() {
    expect:
    new String(JsonCanonicalizer.canonicalize(b: true, c: null, a: false,), StandardCharsets.UTF_8) ==
    '{"a":false,"b":true,"c":null}'
  }

  void 'utf-8 encoding'() {
    def str = '\u0000\u0080\u0800\uDBC0\uDC00'
    expect:
    new String(JsonCanonicalizer.canonicalize(a: str), StandardCharsets.UTF_8) ==
    '{"a":"' + str + '"}'
  }

  void 'serialize numbers'() {
    expect:
    new String(JsonCanonicalizer.canonicalize(a: [-4, -4.5, 92233720368547758075G, Long.MAX_VALUE]), StandardCharsets.UTF_8) ==
    '{"a":[-4,-4,-5,9223372036854775807]}'
  }
}
