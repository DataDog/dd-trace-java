package com.datadog.appsec.event.data

import spock.lang.Specification

class SingletonDataBundleSpecification extends Specification {
  def 'basic methods'() {
    setup:
    DataBundle dataBundle = new SingletonDataBundle(
      KnownAddresses.REQUEST_BODY_OBJECT, 'foo')

    expect:
    dataBundle.hasAddress(KnownAddresses.REQUEST_BODY_OBJECT)
    !dataBundle.hasAddress(KnownAddresses.REQUEST_CLIENT_IP)
    dataBundle.allAddresses == [KnownAddresses.REQUEST_BODY_OBJECT]
    dataBundle.get(KnownAddresses.REQUEST_BODY_OBJECT) == 'foo'
    dataBundle.get(KnownAddresses.REQUEST_CLIENT_IP) == null
    dataBundle.size() == 1
  }

  def iteration() {
    setup:
    DataBundle dataBundle = new SingletonDataBundle(
      KnownAddresses.REQUEST_BODY_OBJECT, 'foo')

    expect:
    for (def entry: dataBundle) {
      entry.key == KnownAddresses.REQUEST_BODY_OBJECT
      entry.value == 'foo'
    }
  }

  def 'iteration related exceptions'() {
    setup:
    DataBundle dataBundle = new SingletonDataBundle(
      KnownAddresses.REQUEST_BODY_OBJECT, 'foo')

    when:
    def iterator = dataBundle.iterator()
    def entry = iterator.next()
    entry.value = 'should throw'

    then:
    thrown UnsupportedOperationException

    when:
    iterator.next()

    then:
    thrown NoSuchElementException
  }
}
