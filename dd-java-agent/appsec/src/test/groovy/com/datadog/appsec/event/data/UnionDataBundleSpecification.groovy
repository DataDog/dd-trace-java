package com.datadog.appsec.event.data

import spock.lang.Specification

class UnionDataBundleSpecification extends Specification {
  DataBundle db = DataBundle.unionOf(
  new SingletonDataBundle(KnownAddresses.REQUEST_URI_RAW, '/'),
  new SingletonDataBundle(KnownAddresses.REQUEST_SCHEME, 'http'),
  )

  def 'size and all addresses'() {
    expect:
    db.size() == 2
    db.allAddresses == [KnownAddresses.REQUEST_URI_RAW, KnownAddresses.REQUEST_SCHEME]
    db.get(KnownAddresses.REQUEST_URI_RAW) == '/'
    db.get(KnownAddresses.REQUEST_SCHEME) == 'http'
    db.hasAddress(KnownAddresses.REQUEST_URI_RAW) == true
    db.hasAddress(KnownAddresses.REQUEST_SCHEME) == true
    db.hasAddress(KnownAddresses.REQUEST_BODY_OBJECT) == false
  }

  def iteration() {
    setup:
    def it = db.iterator()

    expect:
    assert it.hasNext() == true

    with(it.next()) { e ->
      e.key == KnownAddresses.REQUEST_URI_RAW
      e.value == '/'
    }

    assert it.hasNext() == true
    with(it.next()) { e ->
      e.key == KnownAddresses.REQUEST_SCHEME
      e.value == 'http'
    }

    assert it.hasNext() == false

    when:
    it.next()

    then:
    thrown NoSuchElementException
  }
}
