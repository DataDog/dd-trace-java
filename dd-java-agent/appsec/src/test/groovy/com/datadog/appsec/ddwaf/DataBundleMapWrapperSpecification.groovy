package com.datadog.appsec.ddwaf

import com.datadog.appsec.event.data.DataBundle
import com.datadog.appsec.event.data.KnownAddresses
import com.datadog.appsec.event.data.MapDataBundle
import datadog.trace.test.util.DDSpecification

class DataBundleMapWrapperSpecification extends DDSpecification {
  // use ofDelegate with LinkedHashMap to ensure iteration order
  DataBundle dataBundle = MapDataBundle.ofDelegate([
    (KnownAddresses.REQUEST_URI_RAW): '/b',
    (KnownAddresses.REQUEST_CLIENT_IP): '::1'])
  Map<String, Object> mapWrapper =
  new WAFModule.DataBundleMapWrapper(
  [KnownAddresses.REQUEST_URI_RAW], // REQUEST_CLIENT_IP will be filtered into an empty map
  dataBundle)

  void size() {
    expect:
    mapWrapper.size() == 2
  }

  void entrySet() {
    def iter = mapWrapper.entrySet().iterator()
    def elem

    expect:
    iter.hasNext()

    when:
    elem = iter.next()

    then:
    elem.key == KnownAddresses.REQUEST_URI_RAW.key
    elem.value == '/b'

    when:
    elem.value = 'foo'

    then:
    thrown(UnsupportedOperationException)

    when:
    elem = iter.next()

    then:
    elem.key == KnownAddresses.REQUEST_CLIENT_IP.key
    elem.value == [:]
    !iter.hasNext()

    when:
    iter.remove()

    then:
    thrown(UnsupportedOperationException)

    when:
    iter.next()

    then:
    thrown(NoSuchElementException)
  }

  void 'entrySet result supports only iterator'() {
    def es = mapWrapper.entrySet()

    when:
    es.size()

    then:
    thrown(UnsupportedOperationException)
  }

  void 'methods other than entrySet and size are not supported: #methodName'() {
    when:
    method(mapWrapper)

    then:
    thrown(UnsupportedOperationException)

    where:
    methodName      | method
    "values"        | { it.values() }
    "keySet"        | { it.keySet() }
    "clean"         | { it.clear() }
    "putAll"        | { it.putAll([:]) }
    "remove"        | { it.remove('a') }
    "put"           | { it.put('b', 'a') }
    "get"           | { it.get('a') }
    "containsValue" | { it.containsValue('b') }
    "containsKey"   | { it.containsKey('a') }
    "empty"         | { it.isEmpty() }
  }
}
