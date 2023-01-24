package datadog.trace.agent.test.asserts


import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString
import datadog.trace.core.DDSpan
import groovy.transform.stc.ClosureParams
import groovy.transform.stc.SimpleType

import java.util.regex.Pattern

class BaggageAssert {
  private final Map<String, Object> baggage
  private final Set<String> assertedBaggage = new TreeSet<>()

  private BaggageAssert(DDSpan span) {
    this.baggage = span.baggage
  }

  static void assertBaggage(
    DDSpan span,
    @ClosureParams(value = SimpleType, options = ['datadog.trace.agent.test.asserts.BaggageAssert'])
    @DelegatesTo(value = BaggageAssert, strategy = Closure.DELEGATE_FIRST) Closure spec) {
    def asserter = new BaggageAssert(span)
    def clone = (Closure) spec.clone()
    clone.delegate = asserter
    clone.resolveStrategy = Closure.DELEGATE_FIRST
    clone(asserter)
    asserter.assertBaggageVerified()
  }

  def entry(String name, expected) {
    if (expected == null) {
      return
    }
    assertedBaggage.add(name)
    def value = value(name)
    if (expected instanceof Pattern) {
      assert value =~ expected: "Tag \"$name\": \"${value.toString()}\" does not match pattern \"$expected\""
    } else if (expected instanceof Closure) {
      assert ((Closure) expected).call(value): "Tag \"$name\": closure call ${expected.toString()} failed with \"$value\""
    } else if (expected instanceof UTF8BytesString) {
      assert value == expected.toString(): "Tag \"$name\": \"$value\" != \"${expected.toString()}\""
    } else {
      assert value == expected: "Tag \"$name\": \"$value\" != \"$expected\""
    }
  }

  def value(String name) {
    def t = baggage[name]
    return (t instanceof UTF8BytesString) ? t.toString() : t
  }

  def methodMissing(String name, args) {
    if (args.length == 0) {
      throw new IllegalArgumentException(args.toString())
    }
    entry(name, args[0])
  }

  def addEntries(Map<String, Serializable> entries) {
    entries.each { entry(it.key, it.value) }
    true
  }

  void assertBaggageVerified() {
    def set = new TreeMap<>(baggage).keySet()
    set.removeAll(assertedBaggage)
    // The primary goal is to ensure the set is empty.
    // tags and assertedTags are included via an "always true" comparison
    // so they provide better context in the error message.
    assert baggage.entrySet() != assertedBaggage && set.isEmpty()
  }
}
