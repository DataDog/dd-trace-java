package com.datadog.iast.taint


import com.datadog.iast.model.Range
import com.datadog.iast.taint.TaintedMap.WithPurgeInline
import com.datadog.iast.taint.TaintedMap.WithPurgeQueue
import com.datadog.iast.test.ReplaceSlf4jLogger
import datadog.trace.test.util.CircularBuffer
import datadog.trace.test.util.DDSpecification
import org.junit.Rule
import org.slf4j.Logger

import java.lang.ref.Reference
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class TaintedMapTest extends DDSpecification {

  private Logger mockLogger = Mock(Logger)

  @Rule
  ReplaceSlf4jLogger replaceSlf4jLogger = new ReplaceSlf4jLogger(TaintedMap.Debug.getDeclaredField('LOGGER'), mockLogger)

  def 'simple workflow'() {
    given:
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

    where:
    map                   | _
    new WithPurgeQueue()  | _
    new WithPurgeInline() | _
  }

  def 'get non-existent object'() {
    given:
    final o = new Object()

    expect:
    !map.isFlat()
    map.get(o) == null
    map.size() == 0
    map.count() == 0

    where:
    map                   | _
    new WithPurgeQueue()  | _
    new WithPurgeInline() | _
  }

  def 'last put always exists'() {
    given:
    int nTotalObjects = capacity * 10

    expect:
    (1..nTotalObjects).each { i ->
      final o = new Object()
      final to = new TaintedObject(o, [] as Range[], map.getReferenceQueue())
      map.put(to)
      assert map.get(o) == to
    }

    where:
    capacity | map                                                                        | _
    256      | new WithPurgeQueue(capacity, (int) (capacity / 2), new ReferenceQueue<>()) | _
    256      | new WithPurgeInline(capacity)                                              | _
  }

  def 'do not fail on extraneous reference'() {
    given:
    int capacity = 256
    int flatModeThreshold = (int) (capacity / 2)
    def queue = new MockReferenceQueue()
    def map = new WithPurgeQueue(capacity, flatModeThreshold, queue)
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
    def map = new WithPurgeQueue(capacity, flatModeThreshold, queue)
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
    def map = new WithPurgeQueue(capacity, flatModeThreshold, queue)
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
    def map = new WithPurgeQueue(capacity, flatModeThreshold, queue)
    def gen = new ObjectGen(capacity)

    when:
    // Number of purges required to switch to flat mode (in the absence of garbage collection)
    final int objectsToFlatMode = WithPurgeQueue.DEFAULT_FLAT_MODE_THRESHOLD + 1
    gen.genObjects(objectsToFlatMode, ObjectGen.TRIGGERS_PURGE).each { o ->
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
    def map = new WithPurgeQueue(capacity, flatModeThreshold, queue)

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
    def map = new WithPurgeQueue(capacity, flatModeThreshold, queue)

    int iters = 1
    def gen = new ObjectGen(capacity)
    def objectBuffer = new CircularBuffer<Object>(iters)

    when:
    // Number of purges required to switch to flat mode (in the absence of garbage collection)
    final int objectsToFlatMode = WithPurgeQueue.DEFAULT_FLAT_MODE_THRESHOLD + 1
    gen.genObjects(objectsToFlatMode, ObjectGen.TRIGGERS_PURGE).each { o ->
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
    def map = new WithPurgeQueue(capacity, flatModeThreshold, queue)

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
    def map = new WithPurgeQueue(capacity, flatModeThreshold, queue)

    and:
    int nThreads = 16
    def gen = new ObjectGen(capacity)
    def executorService = Executors.newFixedThreadPool(nThreads)
    def latch = new CountDownLatch(nThreads)

    when:
    // Number of purges required to switch to flat mode (in the absence of garbage collection)
    final int objectsToFlatMode = WithPurgeQueue.DEFAULT_FLAT_MODE_THRESHOLD + 1
    gen.genObjects(objectsToFlatMode, ObjectGen.TRUE).each { o ->
      final to = new TaintedObject(o, [] as Range[], map.getReferenceQueue())
      queue.hold(o, to)
      map.put(to)
    }

    then:
    map.isFlat()

    when: 'puts from different threads to any buckets'
    def futures = (0..nThreads - 1).collect { thread ->
      // Each thread has multiple objects for each bucket
      def objects = gen.genBuckets(capacity, 10).flatten()
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
    def map = new WithPurgeQueue(capacity, flatModeThreshold, queue)

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
    !map.isFlat()

    cleanup:
    executorService?.shutdown()
  }

  void 'non queue based map purges elements on put/get'() {
    given:
    final capacity = 1 // single bucket
    final map = new WithPurgeInline(1)
    final gen = new ObjectGen(capacity)
    final to = gen.genObjects(5, ObjectGen.TRUE).collect { new TaintedObject(it, [] as Range[], null) }

    when: 'purging the head with put'
    map.put(to[0])
    to[0].enqueue()
    map.put(to[1])

    then:
    map.size() == 1
    map.count() == 1
    !map.isFlat()

    when: 'purging an element in the middle with put'
    map.put(to[2])
    map.put(to[3])
    to[2].enqueue()
    map.put(to[4])

    then:
    map.size() == 3
    map.count() == 3
    !map.isFlat()

    when: 'purging the tail with get'
    to[4].enqueue()
    map.get('I am not in the map!!!')

    then:
    map.size() == 2
    map.count() == 2
    !map.isFlat()
  }

  void 'test no op implementation'() {
    setup:
    final instance = TaintedMap.NoOp.INSTANCE
    final toTaint = 'test'

    when:
    final tainted = instance.put(new TaintedObject(toTaint, [] as Range[], null))

    then:
    tainted == null
    instance.get(toTaint) == null
    instance.count() == 0
    instance.size() == 0
    !instance.flat
    !instance.iterator().hasNext()
  }

  void 'test debug instance'() {
    given:
    final map = new TaintedMap.Debug(new WithPurgeInline())
    final capacity = TaintedMap.Debug.COMPUTE_STATISTICS_INTERVAL
    final gen = new ObjectGen(capacity)

    when:
    gen.genObjects(capacity, ObjectGen.TRUE).each { map.put(new TaintedObject(it, [] as Range[], null)) }

    then:
    1 * mockLogger.isDebugEnabled() >> true
    1 * mockLogger.debug({
      final string = it as String
      assert string.startsWith('Map [size:')
    })
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
