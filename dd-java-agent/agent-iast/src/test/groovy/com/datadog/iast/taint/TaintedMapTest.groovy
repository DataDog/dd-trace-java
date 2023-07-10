package com.datadog.iast.taint

import com.datadog.iast.model.Range
import datadog.trace.test.util.CircularBuffer
import datadog.trace.test.util.DDSpecification

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
    !map.isFlat()
    map.get(o) == null
    map.size() == 0
    map.count() == 0
  }

  def 'last put always exists'() {
    given:
    int capacity = 256
    int flatModeThreshold = (int) (capacity / 2)
    def map = new TaintedMap(capacity, flatModeThreshold, new ReferenceQueue<>())
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
    int flatModeThreshold = (int) (capacity / 2)
    def queue = new MockReferenceQueue()
    def map = new TaintedMap(capacity, flatModeThreshold, queue)
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
    !map.isFlat()
    map.size() == 1
    map.count() == 1
  }

  def 'do not fail on double free'() {
    given:
    int capacity = 256
    int flatModeThreshold = (int) (capacity / 2)
    def queue = new MockReferenceQueue()
    def map = new TaintedMap(capacity, flatModeThreshold, queue)
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
    !map.isFlat()
    map.size() == 1
    map.count() == 1
  }

  def 'do not fail on double free with previous data'() {
    given:
    int capacity = 256
    int flatModeThreshold = (int) (capacity / 2)
    def queue = new MockReferenceQueue()
    def map = new TaintedMap(capacity, flatModeThreshold, queue)
    def gen = new ObjectGen(capacity)
    def bucket = gen.genBucket(2, ObjectGen.TRIGGERS_PURGE)

    when:
    queue.free(new TaintedObject(bucket[0], new Range[0] as Range[], queue))
    final to = new TaintedObject(bucket[1], [] as Range[], map.getReferenceQueue())
    map.put(to)

    then:
    !map.isFlat()
    map.size() == 1
    map.count() == 1
  }

  def 'flat mode - last put wins'() {
    given:
    int capacity = 256
    int flatModeThreshold = (int) (capacity / 2)
    def queue = new MockReferenceQueue()
    def map = new TaintedMap(capacity, flatModeThreshold, queue)
    def gen = new ObjectGen(capacity)

    when:
    // Number of purges required to switch to flat mode (in the absence of garbage collection)
    final int purgesToFlatMode = (int) (flatModeThreshold / TaintedMap.PURGE_COUNT) + 1
    gen.genObjects(purgesToFlatMode, ObjectGen.TRIGGERS_PURGE).each { o ->
      final to = new TaintedObject(o, [] as Range[], map.getReferenceQueue())
      queue.hold(o, to)
      map.put(to)
    }

    then:
    map.isFlat()

    when:
    def lastPuts = []
    def nonLastPuts = []
    gen.genBuckets(capacity, 2).each { bucket ->
      bucket.each { o ->
        final to = new TaintedObject(o, [] as Range[], map.getReferenceQueue())
        queue.hold(o, to)
        map.put(to)
      }
      lastPuts.add(bucket[-1])
      nonLastPuts.addAll(bucket[0..-2])
    }

    then:
    map.size() == capacity
    map.count() == capacity

    and: 'last puts are present'
    lastPuts.each { o ->
      assert map.get(o).get() == o
    }

    and: 'non-last puts are not present'
    nonLastPuts.each { o ->
      assert map.get(o) == null
    }
  }

  def 'garbage-collected entries are purged'() {
    given:
    int capacity = 128
    int flatModeThreshold = 64
    def queue = new MockReferenceQueue()
    def map = new TaintedMap(capacity, flatModeThreshold, queue)

    int iters = 16
    int nObjectsPerIter = flatModeThreshold - 1
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
    !map.isFlat()
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

  def 'garbage-collected entries are purged in flat mode'() {
    given:
    int capacity = 128
    int flatModeThreshold = 64
    def queue = new MockReferenceQueue()
    def map = new TaintedMap(capacity, flatModeThreshold, queue)

    int iters = 1
    def gen = new ObjectGen(capacity)
    def objectBuffer = new CircularBuffer<Object>(iters)

    when:
    // Number of purges required to switch to flat mode (in the absence of garbage collection)
    final int purgesToFlatMode = (int) (flatModeThreshold / TaintedMap.PURGE_COUNT) + 1
    gen.genObjects(purgesToFlatMode, ObjectGen.TRIGGERS_PURGE).each { o ->
      final to = new TaintedObject(o, [] as Range[], map.getReferenceQueue())
      queue.hold(o, to)
      map.put(to)
    }

    then:
    map.isFlat()

    when:
    (1..iters).each {
      // Clear previous objects
      queue.clear()
      // Trigger purge
      final o = gen.genObjects(1, ObjectGen.TRIGGERS_PURGE)[0]
      final to = new TaintedObject(o, [] as Range[], map.getReferenceQueue())
      objectBuffer.add(o)
      map.put(to)
    }

    then:
    map.isFlat()
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

  def 'multi-threaded with no collisions, no GC, non-flat mode'() {
    given:
    int capacity = 128
    int flatModeThreshold = 64
    def queue = new MockReferenceQueue()
    def map = new TaintedMap(capacity, flatModeThreshold, queue)

    and:
    int nThreads = 16
    int nObjectsPerThread = 1000
    def gen = new ObjectGen(capacity)
    def executorService = Executors.newFixedThreadPool(nThreads)
    def latch = new CountDownLatch(nThreads)
    def buckets = gen.genBuckets(nThreads, nObjectsPerThread, ObjectGen.DOES_NOT_TRIGGER_PURGE)

    when: 'puts from different threads to different buckets'
    def futures = (0..nThreads-1).collect { thread ->
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
    !map.isFlat()
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

  def 'multi-threaded with no collisions, no GC, flat mode'() {
    given:
    int capacity = 128
    int flatModeThreshold = 64
    def queue = new MockReferenceQueue()
    def map = new TaintedMap(capacity, flatModeThreshold, queue)

    and:
    int nThreads = 16
    def gen = new ObjectGen(capacity)
    def executorService = Executors.newFixedThreadPool(nThreads)
    def latch = new CountDownLatch(nThreads)

    when:
    // Number of purges required to switch to flat mode (in the absence of garbage collection)
    final int purgesToFlatMode = (int) (flatModeThreshold / TaintedMap.PURGE_COUNT) + 1
    gen.genObjects(purgesToFlatMode, ObjectGen.TRIGGERS_PURGE).each { o ->
      final to = new TaintedObject(o, [] as Range[], map.getReferenceQueue())
      queue.hold(o, to)
      map.put(to)
    }

    then:
    map.isFlat()

    when: 'puts from different threads to any buckets'
    def futures = (0..nThreads-1).collect { thread ->
      // Each thread has multiple objects for each bucket
      def objects = gen.genBuckets(capacity, 10).flatten()
      def taintedObjects = objects.collect {o ->
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
          map.put(to)
        }
      } as Runnable)
    }
    futures.collect({
      it.get()
    })

    then:
    map.isFlat()
    map.size() == capacity
    map.count() == capacity
    map.toList().findAll({ it.get() != null }).size() == capacity
    map.toList().collect({ it.get() }).toSet().size() == capacity

    cleanup:
    executorService?.shutdown()
  }

  def 'clear is thread-safe (does not throw)'() {
    given:
    int capacity = 128
    int flatModeThreshold = 64
    def queue = new MockReferenceQueue()
    def map = new TaintedMap(capacity, flatModeThreshold, queue)

    and:
    int nThreads = 16
    def gen = new ObjectGen(capacity)
    def executorService = Executors.newFixedThreadPool(nThreads)
    def latch = new CountDownLatch(nThreads)

    when: 'puts from different threads to any buckets'
    def futures = (0..nThreads-1).collect { thread ->
      // Each thread has multiple objects for each bucket
      def objects = gen.genBuckets(capacity, 32).flatten()
      def taintedObjects = objects.collect {o ->
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
    !map.isFlat()

    cleanup:
    executorService?.shutdown()
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
