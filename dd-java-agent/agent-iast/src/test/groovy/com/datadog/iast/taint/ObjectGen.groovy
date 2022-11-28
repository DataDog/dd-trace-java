package com.datadog.iast.taint

import java.util.function.Predicate

import static com.datadog.iast.taint.DefaultTaintedMap.POSITIVE_MASK
import static com.datadog.iast.taint.DefaultTaintedMap.PURGE_MASK

/**
 * Generate objects to test {@link DefaultTaintedMap}.
 */
class ObjectGen {

  final int capacity
  final Map<Integer, List<Object>> pool

  ObjectGen(int capacity) {
    assert (capacity & (capacity - 1)) == 0, 'capacity must be a power of 2'
    this.capacity = capacity
    this.pool = new HashMap<>(capacity)
    for (int i = 0; i < capacity; i++) {
      this.pool.put(i, new ArrayList<Object>())
    }
  }

  def genBuckets(int nBuckets, int nObjects) {
    return genBuckets(nBuckets, nObjects, { true })
  }

  def genBuckets(int nBuckets, int nObjects, Predicate<Integer> isValid) {
    assert nBuckets > 0
    assert nObjects > 0
    def excludedBuckets = new HashSet<Integer>()
    return (1..nBuckets).collect {
      def bucket = genBucket(nObjects, isValid & { !excludedBuckets.contains(it) })
      excludedBuckets.add(getIndex(bucket.get(0)))
      bucket
    }
  }

  def genBucket(int nObjects) {
    assert nObjects > 0
    genBucket(nObjects, new HashSet<Integer>())
  }

  def genBucket(int nObjects, Set<Integer> excludedBuckets) {
    assert nObjects > 0
    genBucket(nObjects, { !excludedBuckets.contains(it) })
  }

  def genBucket(int nObjects, Predicate<Integer> isValid) {
    assert nObjects > 0
    while (true) {
      for (int i = 0; i < capacity; i++) {
        if (!isValid.test(i)) {
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

  def genObjects(int nObjects, Predicate<Integer> isValid) {
    def res = new ArrayList(nObjects)
    while (res.size() < nObjects) {
      def obj = new Object()
      int bucket = getIndex(obj)
      if (isValid.test(bucket)) {
        res.add(obj)
      } else {
        pool.get(bucket).add(obj)
      }
    }
    return res
  }

  private genObject() {
    def obj = new Object()
    int bucket = getIndex(obj)
    pool.get(bucket).add(obj)
    return bucket
  }

  private int getIndex(Object obj) {
    return getIndex(capacity, obj)
  }

  private int getIndex(int capacity, Object obj) {
    int positiveHashCode = System.identityHashCode(obj) & POSITIVE_MASK
    int bucket = positiveHashCode & (capacity - 1)
    return bucket
  }

  public static final Predicate<Integer> TRIGGERS_PURGE = { i -> (i & PURGE_MASK) == 0 }
  public static final Predicate<Integer> DOES_NOT_TRIGGER_PURGE = { i -> (i & PURGE_MASK) != 0 }
}
