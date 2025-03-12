package datadog.trace.bootstrap.instrumentation.api

import datadog.context.Context
import spock.lang.Specification

class BaggageTest extends Specification {
  def 'test empty baggage'() {
    when:
    def baggage = Baggage.empty()

    then:
    baggage.asMap().isEmpty()
    baggage.w3cHeader != null
    baggage.w3cHeader.isEmpty()

    when:
    baggage.addItem('key', 'value')

    then:
    baggage.w3cHeader == null
  }

  def 'test baggage creation'() {
    setup:
    def items = ['key1': 'value1', 'key2': 'value2']
    def header = 'key1=value1,key2=value2'

    when:
    def baggage = Baggage.create(items)

    then:
    baggage.asMap() == items
    baggage.w3cHeader == null

    when:
    baggage = Baggage.create(items, header)

    then:
    baggage.asMap() == items
    baggage.w3cHeader == header
  }

  def 'test baggage header'() {
    setup:
    def items = ['key1': 'value1', 'key2': 'value2']
    def header = 'key1=value1,key2=value2'
    def baggage = Baggage.create(items, header)

    when:
    baggage.removeItem('missingKey')

    then: 'header is preserved'
    baggage.w3cHeader == header

    when:
    baggage.removeItem('key2')

    then: 'header is out of sync'
    baggage.w3cHeader == null

    when:
    baggage.w3cHeader = 'key1=value1'

    then: 'header is forced'
    baggage.w3cHeader == 'key1=value1'

    when:
    baggage.addItem('key3', 'value3')

    then: 'header is out of sync'
    baggage.w3cHeader == null
  }

  def 'test context storage'() {
    given:
    def baggage = Baggage.empty()
    def context = Context.root()

    expect:
    Baggage.fromContext(context) == null

    when:
    context = context.with(baggage)

    then:
    Baggage.fromContext(context) == baggage
  }
}
