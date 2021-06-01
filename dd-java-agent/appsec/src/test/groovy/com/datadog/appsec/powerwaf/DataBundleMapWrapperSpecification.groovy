package com.datadog.appsec.powerwaf

import com.datadog.appsec.event.data.DataBundle
import com.datadog.appsec.event.data.KnownAddresses
import com.datadog.appsec.event.data.MapDataBundle
import spock.lang.Specification

class DataBundleMapWrapperSpecification extends Specification {
  // use ofDelegate with LinkedHashMap to ensure iteration order
  DataBundle dataBundle = MapDataBundle.ofDelegate([
    (KnownAddresses.REQUEST_URI_RAW): '/b',
    (KnownAddresses.REQUEST_CLIENT_IP): '::1'])
  Map<String, Object> mapWrapper = new PowerWAFModule.DataBundleMapWrapper(dataBundle)

  void size() {
    expect:
    mapWrapper.size() == 2
  }

  void entrySet() {
    def iter = mapWrapper.entrySet().iterator()
    def elem

    expect:
    iter.hasNext() == true

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
    iter.hasNext() == false

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

  void 'methods other than entrySet and size are not supported'() {
    when:
    method(mapWrapper)

    then:
    thrown(UnsupportedOperationException)

    where:
    method << [
      { it.values() },
      { it.keySet() },
      { it.clear() },
      { it.putAll([:]) },
      { it.remove('a') },
      { it.put('b', 'a') },
      { it.get('a') },
      { it.containsValue('b') },
      { it.containsKey('a') },
      { it.isEmpty() },
    ]
  }
}
