package com.datadog.appsec.event.data

import datadog.trace.test.util.DDSpecification

class AddressSpecification extends DDSpecification {
  void 'to string'() {
    expect:
    KnownAddresses.REQUEST_URI_RAW as String == 'Address{key=\'server.request.uri.raw\'}'
  }
}
