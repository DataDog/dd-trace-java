package com.datadog.profiling.context;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;

final class TimeBuckets<T> {
  static final class Bucket<T> {
    private int size = 0;
    private final Object[] data;

    Bucket(int capacity) {
      data = new Object[capacity];
    }

    int add(T element) {
      if (size == data.length) {
        return -1;
      }
      int pos = size;
      data[size++] = element;
      return pos;
    }

    void remove(int pos) {
      if (pos > -1) {
        data[pos] = data[--size];
        data[size] = null;
      }
    }
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  static final class StripedBucket<T> {
    private final Bucket[] buckets;
    private final ReentrantLock[] locks;

    private final int numStripes;
    private final int bucketCapacity;

    StripedBucket(int parallelism, int capacity) {
      this.numStripes = parallelism;
      buckets = new Bucket[parallelism];
      locks = new ReentrantLock[parallelism];
      bucketCapacity = (int)Math.ceil((double)capacity / parallelism);
      for (int i = 0; i < parallelism; i++) {
        buckets[i] = new Bucket(bucketCapacity);
        locks[i] = new ReentrantLock();
      }

    }

    public int add(T element) {
      int stripe = ThreadLocalRandom.current().nextInt(numStripes);

      for (int i = 0; i < numStripes; i++) {
        ReentrantLock lock = locks[stripe];
        lock.lock();
        try {
          Bucket<T> bucket = buckets[stripe];
          int ret = bucket.add(element);
          if (ret > -1) {
            return stripe * bucketCapacity + ret;
          }
          stripe = (stripe + 11) % numStripes;
        } finally {
          lock.unlock();
        }
      }
      return -1;
    }

    public void remove(int pos) {
      int stripe = pos / bucketCapacity;
      int bucketPos = pos % bucketCapacity;

      ReentrantLock lock = locks[stripe];
      lock.lock();
      try {
        Bucket<T> bucket = buckets[stripe];
        bucket.remove(bucketPos);
      } finally {
        lock.unlock();
      }
    }
  }
}
