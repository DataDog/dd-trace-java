package com.datadog.appsec.event.data

import datadog.trace.test.util.DDSpecification

class MapDataBundleSpecification extends DDSpecification {

  void 'three pairs variant'() {
    def bundle = MapDataBundle.of(
      KnownAddresses.REQUEST_URI_RAW, '/a',
      KnownAddresses.REQUEST_CLIENT_IP, '::1',
      KnownAddresses.REQUEST_BODY_RAW, '')

    expect:
    bundle.size() == 3
    bundle.get(KnownAddresses.REQUEST_URI_RAW) == '/a'
    bundle.get(KnownAddresses.REQUEST_CLIENT_IP) == '::1'
    bundle.get(KnownAddresses.REQUEST_BODY_RAW) == ''
  }
}
