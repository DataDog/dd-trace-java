package datadog.trace.agent.tooling

import datadog.trace.test.util.GCUtils
import datadog.trace.test.util.DDSpecification
import spock.lang.IgnoreIf

import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit

class WeakMapTest extends DDSpecification {

  def "WeakMap accepts null values"() {
    setup:
    def map = WeakMaps.newWeakMap()

    when:
    map.put('key', null)

    then:
    noExceptionThrown()
  }

  def "Calling newWeakMap creates independent maps"() {
    setup:
    def key = new Object()
    def map1 = WeakMaps.newWeakMap()
    def map2 = WeakMaps.newWeakMap()

    when:
    map1.put(key, "value1")
    map2.put(key, "value2")

    then:
    map1.get(key) == "value1"
    map2.get(key) == "value2"
  }

  //@Flaky("awaitGC usage is flaky")
  @IgnoreIf(reason="Often fails in Semeru runtime", value = { System.getProperty("java.runtime.name").contains("Semeru") })
  def "Unreferenced map gets cleaned up"() {
    setup:
    def map = WeakMaps.newWeakMap()
    def ref = new WeakReference(map)

    when:
    def mapRef = new WeakReference(map)
    map = null
    GCUtils.awaitGC(mapRef)

    then:
    ref.get() == null
  }

  //@Flaky("awaitGC usage is flaky")
  @IgnoreIf(reason="Often fails in Semeru runtime", value = { System.getProperty("java.runtime.name").contains("Semeru") })
  def "Unreferenced keys get cleaned up"() {
    setup:
    def key = new Object()
    def map = WeakMaps.newWeakMap()
    map.put(key, "value")
    GCUtils.awaitGC()

    expect:
    map.size() == 1

    when:
    def keyRef = new WeakReference(key)
    key = null
    GCUtils.awaitGC(keyRef)

    // Sleep enough time for cleanup thread to get scheduled.
    // But on a very slow box (or high load) scheduling may not be exactly predictable
    // so we try a few times.
    int count = 0
    while (map.size() != 0 && count < 10) {
      Thread.sleep(TimeUnit.SECONDS.toMillis(WeakMaps.CLEAN_FREQUENCY_SECONDS))
      count++
    }

    then:
    map.size() == 0
  }
}
