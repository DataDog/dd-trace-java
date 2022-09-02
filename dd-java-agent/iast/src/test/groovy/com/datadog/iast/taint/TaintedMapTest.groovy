package com.datadog.iast.taint

import com.datadog.iast.model.Range
import datadog.trace.test.util.CircularBuffer
import datadog.trace.test.util.DDSpecification

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class TaintedMapTest extends DDSpecification {

  def 'simple workflow'() {
    given:
    def map = new DefaultTaintedMap()
    final o = new Object()
    final to = new TaintedObject(o, [] as Range[], map.getReferenceQueue())

    expect:
    map.size() == 0
    map.toList().size() == 0

    when:
    map.put(to)

    then:
    map.toList().size() == 1

    and:
    map.get(o) != null
    map.get(o).get() == o

    when:
    map.clear()

    then:
    map.toList().size() == 0
  }

  def 'last put always exists'() {
    given:
    int nTotalObjects = DefaultTaintedMap.DEFAULT_CAPACITY
    def map = new DefaultTaintedMap()

    expect:
    (1..nTotalObjects).each { i ->
      final o = new Object()
      final to = new TaintedObject(o, [] as Range[], map.getReferenceQueue())
      map.put(to)
      assert map.get(o) == to
    }
  }

  def 'single-threaded put-intensive workflow without garbage collection interaction and under max size'() {
    given:
    int nTotalObjects = 1024
    int nRetainedObjects = 1024
    def map = new DefaultTaintedMap()
    def objectBuffer = new CircularBuffer<Tuple2<Object, TaintedObject>>(nRetainedObjects)

    when: 'perform puts'
    (1..nTotalObjects).each { i ->
      final o = new Object()
      final to = new TaintedObject(o, [] as Range[], map.getReferenceQueue())
      objectBuffer.add(new Tuple2(o, to))
      map.put(to)
    }

    then: 'map contains exact amount of objects'
    map.toList().size() == nTotalObjects

    and: 'all objects are as expected'
    objectBuffer.each {
      assert map.get(it.get(0)) == it.get(1)
    }
  }

  def 'single-threaded put-intensive workflow with garbage collection interaction and under max size'() {
    given:
    int nTotalObjects = 1024 * 2
    int nRetainedObjects = 1024
    def map = new DefaultTaintedMap()
    def objectBuffer = new CircularBuffer<Tuple2<Object, TaintedObject>>(nRetainedObjects)

    when: 'perform puts'
    (1..nTotalObjects).each { i ->
      final o = new Object()
      final to = new TaintedObject(o, [] as Range[], map.getReferenceQueue())
      objectBuffer.add(new Tuple2(o, to))
      map.put(to)
    }

    then: 'map contains exact amount of objects'
    map.toList().size() == nTotalObjects

    and: 'all objects are as expected'
    objectBuffer.each {
      assert map.get(it.get(0)) == it.get(1)
    }
  }

  def 'single-threaded put-intensive workflow without garbage collection interaction and over max size'() {
    given:
    int nTotalObjects = DefaultTaintedMap.DEFAULT_FLAT_MODE_THRESHOLD * 4
    int nRetainedObjects = nTotalObjects
    def map = new DefaultTaintedMap()
    def objectBuffer = new CircularBuffer<Tuple2<Object, TaintedObject>>(nRetainedObjects)

    when: 'perform puts'
    (1..nTotalObjects).each { i ->
      final o = new Object()
      final to = new TaintedObject(o, [] as Range[], map.getReferenceQueue())
      objectBuffer.add(new Tuple2(o, to))
      map.put(to)
    }

    then: 'map contains enough objects'
    map.toList().size() >= DefaultTaintedMap.DEFAULT_FLAT_MODE_THRESHOLD * 0.9

    and: 'all objects are as expected'
    int presentObjects = objectBuffer.count {
      map.get(it.get(0)) == it.get(1)
    }
    presentObjects >= DefaultTaintedMap.DEFAULT_FLAT_MODE_THRESHOLD * 0.9
  }

  def 'single-threaded put-intensive workflow with garbage collection interaction and over max size'() {
    given:
    int nTotalObjects = DefaultTaintedMap.DEFAULT_FLAT_MODE_THRESHOLD * 4
    int nRetainedObjects = 1024
    def map = new DefaultTaintedMap()
    def objectBuffer = new CircularBuffer<Tuple2<Object, Object>>(nRetainedObjects)

    when: 'perform puts'
    (1..nTotalObjects).each { i ->
      final o = new Object()
      final to = new TaintedObject(o, [] as Range[], map.getReferenceQueue())
      objectBuffer.add(new Tuple2(o, to))
      map.put(to)
    }

    then: 'map contains enough objects'
    map.toList().size() >= DefaultTaintedMap.DEFAULT_FLAT_MODE_THRESHOLD * 0.6

    and: 'all objects are as expected'
    // FIXME: This might need improvement, we remove too many new objects.
    int presentObjects = objectBuffer.count {
      map.get(it.get(0)) == it.get(1)
    }
    presentObjects >= nRetainedObjects * 0.9
  }

  def 'multi-threaded put-intensive workflow without garbage collection interaction and under max size'() {
    given:
    int maxAcceptableLoss = 100
    int nThreads = 32
    int nObjectsPerThread = (int) Math.floor((DefaultTaintedMap.DEFAULT_FLAT_MODE_THRESHOLD - 4096) / nThreads)
    int nTotalObjects = nThreads * nObjectsPerThread
    def executorService = Executors.newFixedThreadPool(nThreads)
    def startLatch = new CountDownLatch(nThreads)
    def map = new DefaultTaintedMap()

    // Holder to avoid objects being garbage collected
    def objectHolder = new ConcurrentHashMap<Object, TaintedObject>()

    when: 'perform a high amount of concurrent puts'
    def futures = (1..nThreads).collect { thread ->
      executorService.submit({
        ->
        final tuples = new ArrayList<Tuple2<Object, TaintedObject>>(nObjectsPerThread)
        for (int i = 1; i <= nObjectsPerThread; i++) {
          final o = new Object()
          final to = new TaintedObject(o, [] as Range[], map.getReferenceQueue())
          tuples.add(new Tuple2<Object, TaintedObject>(o, to))
          objectHolder.put(o, to)
        }
        startLatch.countDown()
        startLatch.await()
        tuples.each { map.put(it.get(1)) }
      } as Runnable)
    }
    futures.collect({
      it.get()
    })

    then: 'map does not contain extra objects'
    map.toList().size() <= nTotalObjects

    and: 'map did not lose too many objects'
    map.toList().size() >= nTotalObjects - maxAcceptableLoss

    and: 'sanity check'
    objectHolder.size() == nTotalObjects

    and: 'all objects are as expected'
    objectHolder.count {
      map.get(it.getKey()) == it.getValue()
    } >= nTotalObjects - maxAcceptableLoss

    cleanup:
    executorService?.shutdown()
  }
}
