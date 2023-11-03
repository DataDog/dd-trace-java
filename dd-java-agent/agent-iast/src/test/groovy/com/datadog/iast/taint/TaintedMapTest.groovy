package com.datadog.iast.taint

import com.datadog.iast.model.Range
import datadog.trace.test.util.DDSpecification

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class TaintedMapTest extends DDSpecification {

  def 'simple workflow'() {
    given:
    def map = new TaintedMap()
    final o = new Object()
    final to = new TaintedObject(o, [] as Range[])

    expect:
    map.size() == 0
    map.count() == 0

    when:
    map.put(to)

    then:
    map.size() == 1
    map.count() == 1

    and:
    map.get(o) != null
    map.get(o).get() == o

    when:
    map.clear()

    then:
    map.size() == 0
    map.count() == 0
  }

  def 'get non-existent object'() {
    given:
    def map = new TaintedMap()
    final o = new Object()

    expect:
    map.get(o) == null
    map.size() == 0
    map.count() == 0
  }

  def 'last put always exists'() {
    given:
    int capacity = 256
    def map = new TaintedMap(capacity)
    int nTotalObjects = capacity * 10

    expect:
    (1..nTotalObjects).each { i ->
      final o = new Object()
      final to = new TaintedObject(o, [] as Range[])
      map.put(to)
      assert map.get(o) == to
    }
  }

  def 'garbage-collected entries are purged'() {
    given:
    int capacity = 128
    final objectBuffer = Collections.newSetFromMap(new IdentityHashMap<Object, ?>(capacity))
    def map = new MockTaintedMap(capacity, objectBuffer)

    int iters = 16
    int nObjectsPerIter = (int) (capacity / 2) - 1
    def gen = new ObjectGen(capacity)

    when:
    (1..iters).each {
      // Insert objects to be purged
      final toPurge = gen.genObjects(nObjectsPerIter).each { o ->
        objectBuffer.add(o)
        def to = new TaintedObject(o, [] as Range[])
        map.put(to)
      }
      objectBuffer.removeAll(toPurge)

      // Insert surviving object
      final o = gen.genObjects(1)[0]
      objectBuffer.add(o)
      final to = new TaintedObject(o, [] as Range[])
      map.put(to)

      // Trigger purge
      map.purge()
    }

    then:
    map.size() == iters
    map.count() == iters
    final entries = map.toList()
    entries.findAll { it.get() != null }.size() == iters

    and: 'all objects are as expected'
    objectBuffer.each { o ->
      final to = map.get(o)
      assert to != null
      assert to.get() == o
    }
  }

  def 'multi-threaded with no collisions, no GC'() {
    given:
    int capacity = 128
    def map = new TaintedMap(capacity)

    and:
    int nThreads = 16
    int nObjectsPerThread = 1000
    def gen = new ObjectGen(capacity)
    def executorService = Executors.newFixedThreadPool(nThreads)
    def latch = new CountDownLatch(nThreads)
    def buckets = gen.genBuckets(nThreads, nObjectsPerThread)

    when: 'puts from different threads to different buckets'
    def futures = (0..nThreads - 1).collect { thread ->
      executorService.submit({
        ->
        latch.countDown()
        latch.await()
        buckets[thread].each { o ->
          final to = new TaintedObject(o, [] as Range[])
          map.put(to)
        }
      } as Runnable)
    }
    futures.collect({
      it.get()
    })

    then:
    nThreads == buckets.size()

    and: 'all objects are as expected'
    buckets.each { bucket ->
      bucket.each { o ->
        assert map.get(o) != null
        assert map.get(o).get() == o
      }
    }

    cleanup:
    executorService?.shutdown()
  }

  def 'clear is thread-safe (does not throw)'() {
    given:
    int capacity = 128
    def map = new TaintedMap(capacity)

    and:
    int nThreads = 16
    def gen = new ObjectGen(capacity)
    def executorService = Executors.newFixedThreadPool(nThreads)
    def latch = new CountDownLatch(nThreads)

    when: 'puts from different threads to any buckets'
    def futures = (0..nThreads - 1).collect { thread ->
      // Each thread has multiple objects for each bucket
      def objects = gen.genBuckets(capacity, 32).flatten()
      def taintedObjects = objects.collect { o ->
        return new TaintedObject(o, [] as Range[])
      }
      Collections.shuffle(taintedObjects)

      executorService.submit({
        ->
        latch.countDown()
        latch.await()
        taintedObjects.each { to ->
          if (System.identityHashCode(to) % 10 == 0) {
            map.clear()
          }
          map.put(to)
        }
      } as Runnable)
    }
    futures.collect({
      it.get()
    })
    map.clear()

    then:
    map.size() == 0
    map.count() == 0

    cleanup:
    executorService?.shutdown()
  }

  void 'ensure stale objects are properly removed'() {
    given:
    final objects = Collections.newSetFromMap(new IdentityHashMap<String, ?>())
    final map = new MockTaintedMap(1, objects) // same bucket
    (1..10).each {objects.add("Item$it".toString()) }

    when:
    objects.each { map.put(new TaintedObject(it, [] as Range[])) }

    then:
    map.size() == objects.size()

    when:
    objects.removeAll { it.toString().replaceAll('[^0-9]', '').toInteger() % 2 == 1 }

    then: 'all objects remain'
    map.size() == 10

    when:
    final last = 'My newly created object'
    objects.add(last)
    map.put(new TaintedObject(last, [] as Range[]))

    then:
    map.size() == objects.size()
  }

  private static class MockTaintedMap extends TaintedMap {

    private final Set<Object> alive

    MockTaintedMap(Set<Object> alive) {
      this(DEFAULT_CAPACITY, alive)
    }

    MockTaintedMap(int capacity, Set<Object> alive) {
      super(capacity)
      this.alive = alive
    }

    @Override
    protected boolean isAlive(TaintedObject to) {
      return to.get() != null && alive.contains(to.get())
    }
  }
}
