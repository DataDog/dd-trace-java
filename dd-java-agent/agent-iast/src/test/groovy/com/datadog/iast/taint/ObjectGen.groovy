package com.datadog.iast.taint

import static TaintedMap.POSITIVE_MASK

import groovy.transform.CompileStatic

/**
 * Generate objects to test {@link TaintedMap}.
 */
@CompileStatic // Workaround to avoid java.lang.AbstractMethodError with Groovy 4.
class ObjectGen {

  final int capacity
  final Map<Integer, List<Object>> pool
  final Closure<Object> factory

  ObjectGen(int capacity, Closure<Object> factory = { new Object() }) {
    assert (capacity & (capacity - 1)) == 0, 'capacity must be a power of 2'
    this.capacity = capacity
    this.pool = new HashMap<>(capacity)
    for (int i = 0; i < capacity; i++) {
      this.pool.put(i, new ArrayList<Object>())
    }
    this.factory = factory
  }

  def genBuckets(int nBuckets, int nObjects) {
    return genBuckets(nBuckets, nObjects, TRUE)
  }

  def genBuckets(int nBuckets, int nObjects, Closure<Boolean> isValid) {
    assert nBuckets > 0
    assert nObjects > 0
    def excludedBuckets = new HashSet<Integer>()
    return (1..nBuckets).collect {
      def bucket = genBucket(nObjects, { isValid.call(it) && !excludedBuckets.contains(it) })
      excludedBuckets.add(getIndex(bucket.get(0)))
      bucket
    }
  }

  // Have to return specific type to avoid java.lang.AbstractMethodError with Groovy 4.
  List<Object> genBucket(int nObjects, Closure<Boolean> isValid) {
    assert nObjects > 0
    while (true) {
      for (int i = 0; i < capacity; i++) {
        if (!isValid.call(i)) {
          continue
        }
        def objLst = pool.get(i)
        if (objLst.size() >= nObjects) {
          def res = new ArrayList<>(objLst[0..nObjects-1])
          pool.put(i, objLst.drop(nObjects))
          return res
        }
      }
      for (int i = 0; i < capacity; i++) {
        genObject()
      }
    }
  }

  def genObjects(int nObjects, Closure<Boolean> isValid) {
    def res = new ArrayList(nObjects)
    while (res.size() < nObjects) {
      def obj = factory.call()
      int bucket = getIndex(obj)
      if (isValid.call(bucket)) {
        res.add(obj)
      } else {
        pool.get(bucket).add(obj)
      }
    }
    return res
  }

  def genObject() {
    def obj = factory.call()
    int bucket = getIndex(obj)
    pool.get(bucket).add(obj)
    return bucket
  }

  int getIndex(Object obj) {
    return getIndex(capacity, obj)
  }

  int getIndex(int capacity, Object obj) {
    int positiveHashCode = System.identityHashCode(obj) & POSITIVE_MASK
    int bucket = positiveHashCode & (capacity - 1)
    return bucket
  }

  static final Closure<Boolean> TRUE = { i -> true }
}
