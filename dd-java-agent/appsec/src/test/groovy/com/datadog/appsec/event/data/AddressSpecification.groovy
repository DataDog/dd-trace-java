package com.datadog.appsec.event.data

import spock.lang.Specification

class AddressSpecification extends Specification {
  void 'to string'() {
    expect:
    KnownAddresses.REQUEST_URI_RAW as String == 'Address{key=\'server.request.uri.raw\'}'
  }
}
