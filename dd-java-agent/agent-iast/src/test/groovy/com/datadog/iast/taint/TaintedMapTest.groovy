package com.datadog.iast.taint

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.datadog.iast.model.Range
import datadog.trace.test.util.CircularBuffer
import datadog.trace.test.util.DDSpecification

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class TaintedMapTest extends DDSpecification {


  private Logger logger
  private Level defaultLevel

  void setup() {
    logger = TaintedMap.Debug.LOGGER as Logger
    defaultLevel = logger.getLevel()
  }

  void cleanup() {
    logger.setLevel(defaultLevel)
  }

  def 'simple workflow'() {
    given:
    final map = new TaintedMap.TaintedMapImpl()
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
    final map = new TaintedMap.TaintedMapImpl()
    final o = new Object()

    expect:
    map.get(o) == null
    map.size() == 0
    map.count() == 0
  }

  def 'last put always exists'() {
    given:
    int capacity = 256
    final map = new TaintedMap.TaintedMapImpl()
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
    int flatModeThreshold = 64
    def map = new TaintedMap.TaintedMapImpl(capacity)

    int iters = 16
    int nObjectsPerIter = flatModeThreshold - 1
    def gen = new ObjectGen(capacity)
    def objectBuffer = new CircularBuffer<Object>(iters)

    when:
    (1..iters).each {
      final queue = gen.genObjects(nObjectsPerIter, ObjectGen.TRUE).collect { o ->
        final to = new TaintedObject(o, [] as Range[])
        map.put(to)
        return to
      }
      // Clear previous objects
      queue.each {
        final referent = it.get()
        it.enqueue()
        map.get(referent)
      }
      queue.clear()
      // Trigger purge
      final o = gen.genObjects(1, ObjectGen.TRUE)[0]
      final to = new TaintedObject(o, [] as Range[])
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
    def map = new TaintedMap.TaintedMapImpl(capacity)

    and:
    int nThreads = 16
    int nObjectsPerThread = 1000
    def gen = new ObjectGen(capacity)
    def executorService = Executors.newFixedThreadPool(nThreads)
    def latch = new CountDownLatch(nThreads)
    def buckets = gen.genBuckets(nThreads, nObjectsPerThread, ObjectGen.TRUE)

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
    def map = new TaintedMap.TaintedMapImpl(capacity)

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

  void 'map purges elements on put/get'() {
    given:
    final capacity = 1 // single bucket
    final map = new TaintedMap.TaintedMapImpl(1)
    final gen = new ObjectGen(capacity)
    final to = gen.genObjects(5, ObjectGen.TRUE).collect { new TaintedObject(it, [] as Range[]) }

    when: 'purging the head with put'
    map.put(to[0])
    to[0].enqueue()
    map.put(to[1])

    then:
    map.size() == 1
    map.count() == 1

    when: 'purging an element in the middle with put'
    map.put(to[2])
    map.put(to[3])
    to[2].enqueue()
    map.put(to[4])

    then:
    map.size() == 3
    map.count() == 3

    when: 'purging the tail with get'
    to[4].enqueue()
    map.get('I am not in the map!!!')

    then:
    map.size() == 2
    map.count() == 2
  }

  void 'test no op implementation'() {
    setup:
    final instance = TaintedMap.NoOp.INSTANCE
    final toTaint = 'test'

    when:
    final tainted = instance.put(new TaintedObject(toTaint, [] as Range[]))

    then:
    tainted == null
    instance.get(toTaint) == null
    instance.count() == 0
    instance.size() == 0
    !instance.iterator().hasNext()
  }

  void 'test debug instance'() {
    setup:
    final map = new TaintedMap.Debug(new TaintedMap.TaintedMapImpl())
    final capacity = TaintedMap.Debug.COMPUTE_STATISTICS_INTERVAL
    final gen = new ObjectGen(capacity)
    logger.setLevel(Level.ALL)

    when:
    gen.genObjects(capacity, ObjectGen.TRUE).each { map.put(new TaintedObject(it, [] as Range[])) }

    then:
    map.size() == capacity
    noExceptionThrown()
  }
}
