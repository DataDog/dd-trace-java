package com.datadog.iast.taint

import com.datadog.iast.model.Range
import datadog.trace.test.util.CircularBuffer
import datadog.trace.test.util.DDSpecification
import datadog.trace.test.util.GCUtils

import java.lang.ref.Reference
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class TaintedMapTest extends DDSpecification {

  def 'simple workflow'() {
    given:
    def map = new TaintedMap()
    final o = new Object()
    final to = new TaintedObject(o, [] as Range[], map.getReferenceQueue())

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
    def map = new TaintedMap(capacity, new ReferenceQueue<>())
    int nTotalObjects = capacity * 10

    expect:
    (1..nTotalObjects).each { i ->
      final o = new Object()
      final to = new TaintedObject(o, [] as Range[], map.getReferenceQueue())
      map.put(to)
      assert map.get(o) == to
    }
  }

  def 'do not fail on extraneous reference'() {
    given:
    int capacity = 256
    def queue = new MockReferenceQueue()
    def map = new TaintedMap(capacity, queue)
    def gen = new ObjectGen(capacity)

    when: 'extraneous reference in enqueued'
    queue.free(new WeakReference<Object>(new Object()))

    and: 'purge is triggered'
    gen.genObjects(1, ObjectGen.TRIGGERS_PURGE).each { o ->
      final to = new TaintedObject(o, [] as Range[], map.getReferenceQueue())
      queue.hold(o, to)
      map.put(to)
    }

    then:
    map.size() == 1
    map.count() == 1
  }

  def 'do not fail on double free'() {
    given:
    int capacity = 256
    def queue = new MockReferenceQueue()
    def map = new TaintedMap(capacity, queue)
    def gen = new ObjectGen(capacity)

    when: 'reference to non-present object in enqueued'
    queue.free(new TaintedObject(new Object(), new Range[0] as Range[], queue))

    and: 'purge is triggered'
    gen.genObjects(1, ObjectGen.TRIGGERS_PURGE).each { o ->
      final to = new TaintedObject(o, [] as Range[], map.getReferenceQueue())
      queue.hold(o, to)
      map.put(to)
    }

    then:
    map.size() == 1
    map.count() == 1
  }

  def 'do not fail on double free with previous data'() {
    given:
    int capacity = 256
    def queue = new MockReferenceQueue()
    def map = new TaintedMap(capacity, queue)
    def gen = new ObjectGen(capacity)
    def bucket = gen.genBucket(2, ObjectGen.TRIGGERS_PURGE)

    when:
    queue.free(new TaintedObject(bucket[0], new Range[0] as Range[], queue))
    final to = new TaintedObject(bucket[1], [] as Range[], map.getReferenceQueue())
    map.put(to)

    then:
    map.size() == 1
    map.count() == 1
  }

  def 'garbage-collected entries are purged'() {
    given:
    int capacity = 128
    def queue = new MockReferenceQueue()
    def map = new TaintedMap(capacity, queue)

    int iters = 16
    int nObjectsPerIter = (int) (capacity / 2) - 1
    def gen = new ObjectGen(capacity)
    def objectBuffer = new CircularBuffer<Object>(iters)

    when:
    (1..iters).each {
      // Insert objects that do not trigger purge
      gen.genObjects(nObjectsPerIter, ObjectGen.DOES_NOT_TRIGGER_PURGE).each { o ->
        final to = new TaintedObject(o, [] as Range[], map.getReferenceQueue())
        queue.hold(o, to)
        map.put(to)
      }
      // Clear previous objects
      queue.clear()
      // Trigger purge
      final o = gen.genObjects(1, ObjectGen.TRIGGERS_PURGE)[0]
      final to = new TaintedObject(o, [] as Range[], map.getReferenceQueue())
      objectBuffer.add(o)
      map.put(to)
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
    def queue = new MockReferenceQueue()
    def map = new TaintedMap(capacity, queue)

    and:
    int nThreads = 16
    int nObjectsPerThread = 1000
    def gen = new ObjectGen(capacity)
    def executorService = Executors.newFixedThreadPool(nThreads)
    def latch = new CountDownLatch(nThreads)
    def buckets = gen.genBuckets(nThreads, nObjectsPerThread, ObjectGen.DOES_NOT_TRIGGER_PURGE)

    when: 'puts from different threads to different buckets'
    def futures = (0..nThreads - 1).collect { thread ->
      executorService.submit({
        ->
        latch.countDown()
        latch.await()
        buckets[thread].each { o ->
          final to = new TaintedObject(o, [] as Range[], map.getReferenceQueue())
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
    def queue = new MockReferenceQueue()
    def map = new TaintedMap(capacity, queue)

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
        final to = new TaintedObject(o, [] as Range[], map.getReferenceQueue())
        queue.hold(o, to)
        return to
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
    given: 'a map that gets not updated by the GC'
    final queue = new ReferenceQueue()
    final map = new TaintedMap(1)
    final objects = (1..10).collect { "Item$it".toString() }

    when: 'adding objects with strong references'
    objects.each { map.put(new TaintedObject(it, [] as Range[], queue)) }

    then: 'objects are present'
    map.size() == objects.size()

    when: 'GCing some elements'
    objects.remove(objects.size() >> 1)
    objects.remove(objects.size() - 1)
    objects.remove(0)
    GCUtils.awaitGC()
    map.purge()

    then: 'all objects remain'
    map.size() == objects.size() + 3

    when: 'adding a new element'
    final last = 'My newly created object'
    objects.add(last)
    map.put(new TaintedObject(last, [] as Range[], queue))

    then: 'GCed elements are removed'
    map.size() == objects.size()
  }

  private static class MockReferenceQueue extends ReferenceQueue<Object> {
    private List<Reference<?>> queue = new ArrayList()
    private Map<Object, Reference<?>> objects = new HashMap<>()

    void hold(Object referent, Reference<?> reference) {
      objects.put(referent, reference)
    }

    void free(Reference<?> ref) {
      def referent = ref.get()
      ref.clear()
      queue.push(ref)
      if (referent != null) {
        objects.remove(referent)
      }
    }

    void clear() {
      objects.values().toList().each {
        free(it)
      }
    }

    @Override
    Reference<?> poll() {
      if (queue.isEmpty()) {
        return null
      }
      return queue.pop()
    }

    @Override
    Reference<?> remove() throws InterruptedException {
      throw new UnsupportedOperationException("NOT IMPLEMENTED")
    }

    @Override
    Reference<?> remove(long timeout) throws IllegalArgumentException, InterruptedException {
      throw new UnsupportedOperationException("NOT IMPLEMENTED")
    }
  }
}
