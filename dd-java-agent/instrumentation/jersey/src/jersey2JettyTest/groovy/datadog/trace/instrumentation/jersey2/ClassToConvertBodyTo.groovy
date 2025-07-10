package datadog.trace.instrumentation.jersey2

import groovy.json.JsonBuilder

class ClassToConvertBodyTo {
  String a

  @Override
  String toString() {
    new JsonBuilder([a: a]).toString()
  }
}
