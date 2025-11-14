package datadog.trace.api.openfeature.exposure

import datadog.trace.api.openfeature.exposure.dto.Allocation
import datadog.trace.api.openfeature.exposure.dto.ExposureEvent
import datadog.trace.api.openfeature.exposure.dto.Flag
import datadog.trace.api.openfeature.exposure.dto.Subject
import datadog.trace.api.openfeature.exposure.dto.Variant
import spock.lang.Specification

class  LRUExposureCacheTest extends Specification {

  void 'test adding elements'() {
    given:
    final cache = new LRUExposureCache(5)
    final event = createEvent('flag', 'subject', 'variant', 'allocation')

    when:
    final added = cache.add(event)

    then:
    added
    cache.size() == 1
  }

  void 'test adding duplicate events returns false'() {
    given:
    final cache = new LRUExposureCache(5)
    final event = createEvent('flag', 'subject', 'variant', 'allocation')

    when:
    cache.add(event)
    final duplicateAdded = cache.add(event)

    then:
    !duplicateAdded
    cache.size() == 1
  }

  void 'test adding events with same key but different details updates cache'() {
    given:
    final cache = new LRUExposureCache(5)
    final event1 = createEvent('flag', 'subject', 'variant1', 'allocation1')
    final event2 = createEvent('flag', 'subject', 'variant2', 'allocation2')
    final key = new ExposureCache.Key(event1)

    when:
    final added1 = cache.add(event1)
    final added2 = cache.add(event2)
    final retrieved = cache.get(key)

    then:
    added1
    added2
    cache.size() == 1
    retrieved.variant == 'variant2'
    retrieved.allocation == 'allocation2'
  }

  void 'test LRU eviction when capacity exceeded'() {
    given:
    final cache = new LRUExposureCache(2)
    final event1 = createEvent('flag1', 'subject1', 'variant1', 'allocation1')
    final event2 = createEvent('flag2', 'subject2', 'variant2', 'allocation2')
    final event3 = createEvent('flag3', 'subject3', 'variant3', 'allocation3')
    final key1 = new ExposureCache.Key(event1)
    final key3 = new ExposureCache.Key(event3)

    when:
    cache.add(event1)
    cache.add(event2)
    cache.add(event3)

    then:
    cache.size() == 2
    cache.get(key1) == null // event1 should be evicted
    cache.get(key3) != null // event3 should be present
    cache.get(key3).variant == 'variant3'
    cache.get(key3).allocation == 'allocation3'
  }

  void 'test single capacity cache'() {
    given:
    final cache = new LRUExposureCache(1)
    final event1 = createEvent('flag1', 'subject1', 'variant1', 'allocation1')
    final event2 = createEvent('flag2', 'subject2', 'variant2', 'allocation2')

    when:
    cache.add(event1)
    cache.add(event2)

    then:
    cache.size() == 1
  }

  void 'test zero capacity cache'() {
    given:
    final cache = new LRUExposureCache(0)
    final event = createEvent('flag', 'subject', 'variant', 'allocation')

    when:
    final added = cache.add(event)

    then:
    added
    cache.size() == 0
  }

  void 'test empty cache size'() {
    given:
    final cache = new LRUExposureCache(5)

    expect:
    cache.size() == 0
  }

  void 'test multiple additions with same flag different subjects'() {
    given:
    final cache = new LRUExposureCache(10)
    final events = []
    for (int i = 0; i < 5; i++) {
      events << createEvent('flag', "subject${i}", 'variant', 'allocation')
    }

    when:
    def results = events.collect { cache.add(it) }

    then:
    results.every { it == true }
    cache.size() == 5
  }

  void 'test multiple additions with same subject different flags'() {
    given:
    final cache = new LRUExposureCache(10)
    final events = []
    for (int i = 0; i < 5; i++) {
      events << createEvent("flag${i}", 'subject', 'variant', 'allocation')
    }

    when:
    def results = events.collect { cache.add(it) }

    then:
    results.every { it == true }
    cache.size() == 5
  }

  void 'test key equality with null values'() {
    given:
    final cache = new LRUExposureCache(5)
    final event1 = new ExposureEvent(
      System.currentTimeMillis(),
      new Allocation('allocation'),
      new Flag(null),
      new Variant('variant'),
      new Subject(null, [:])
      )
    final event2 = new ExposureEvent(
      System.currentTimeMillis(),
      new Allocation('allocation'),
      new Flag(null),
      new Variant('variant'),
      new Subject(null, [:])
      )

    when:
    cache.add(event1)
    final duplicateAdded = cache.add(event2)

    then:
    !duplicateAdded
    cache.size() == 1
  }

  void 'test updating existing key maintains LRU position'() {
    given:
    final cache = new LRUExposureCache(3)
    final event1 = createEvent('flag1', 'subject1', 'variant1', 'allocation1')
    final event2 = createEvent('flag2', 'subject2', 'variant2', 'allocation2')
    final event3 = createEvent('flag3', 'subject3', 'variant3', 'allocation3')
    final event1Updated = createEvent('flag1', 'subject1', 'variant2', 'allocation2')
    final event4 = createEvent('flag4', 'subject4', 'variant4', 'allocation4')
    final key1 = new ExposureCache.Key(event1)
    final key2 = new ExposureCache.Key(event2)
    final key4 = new ExposureCache.Key(event4)

    when:
    cache.add(event1)
    cache.add(event2)
    cache.add(event3)
    cache.add(event1Updated) // Updates event1, moves to most recent
    cache.add(event4) // Should evict event2, not event1

    then:
    cache.size() == 3
    cache.get(key1) != null // event1 should be updated and present
    cache.get(key1).variant == 'variant2' // verify it was updated
    cache.get(key1).allocation == 'allocation2'
    cache.get(key2) == null // event2 should be evicted
    cache.get(key4) != null // event4 should be present
    cache.get(key4).variant == 'variant4'
  }

  private static ExposureEvent createEvent(String flag, String subject, String variant, String allocation) {
    return new ExposureEvent(
      System.currentTimeMillis(),
      new Allocation(allocation),
      new Flag(flag),
      new Variant(variant),
      new Subject(subject, [:])
      )
  }
}
