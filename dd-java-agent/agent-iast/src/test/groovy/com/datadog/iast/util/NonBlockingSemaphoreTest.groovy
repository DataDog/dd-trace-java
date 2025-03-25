package com.datadog.iast.util


import groovy.transform.CompileDynamic
import spock.lang.Specification

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@CompileDynamic
class NonBlockingSemaphoreTest extends Specification {

  void 'test that the semaphore controls access to a shared resource (#permitCount)'(final int permitCount) {
    given:
    final int threads = 100
    final semaphore = NonBlockingSemaphore.withPermitCount(permitCount)
    final latch = new CountDownLatch(threads)
    final executors = Executors.newFixedThreadPool(threads)

    when:
    final acquired = (1..threads).collect {
      executors.submit({
        latch.countDown()
        if (semaphore.acquire()) {
          TimeUnit.MILLISECONDS.sleep(100)
          semaphore.release()
          return 1
        }
        return 0
      } as Callable<Integer>)
    }.collect { it.get() }.sum()

    then:
    acquired >= permitCount
    acquired < threads
    semaphore.available() == permitCount

    where:
    permitCount | _
    1           | _
    2           | _
  }

  void 'there is resource starvation if the semaphore is not released (#permitCount)'(final int permitCount) {
    given:
    final int threads = 100
    final semaphore = NonBlockingSemaphore.withPermitCount(permitCount)
    final latch = new CountDownLatch(threads)
    final executors = Executors.newFixedThreadPool(threads)

    when:
    final acquired = (1..threads).collect {
      executors.submit ({
        latch.countDown()
        if (semaphore.acquire()) {
          TimeUnit.MILLISECONDS.sleep(100)
          return 1
        }
        return 0
      } as Callable<Integer>)
    }.collect { it.get() }.sum()

    then:
    acquired == permitCount
    semaphore.available() == 0

    where:
    permitCount | _
    1           | _
    2           | _
  }

  void 'can never acquire more permits than the total'(final int permitCount) {
    given:
    final semaphore = NonBlockingSemaphore.withPermitCount(permitCount)

    when:
    final acquired = semaphore.acquire(permitCount+1)

    then:
    !acquired

    where:
    permitCount | _
    1           | _
    2           | _
  }

  void 'can perform extra releases'(final int permitCount) {
    given:
    final semaphore = NonBlockingSemaphore.withPermitCount(permitCount)

    when:
    for (int i = 0; i < permitCount * 2; i++) {
      assert semaphore.release() == permitCount
    }

    then:
    semaphore.available() == permitCount

    where:
    permitCount | _
    1           | _
    2           | _
  }

  void 'reset helps recover when there is starvation (#permitCount)'(final int permitCount) {
    given:
    final semaphore = NonBlockingSemaphore.withPermitCount(permitCount)

    when:
    (1..permitCount).each { semaphore.acquire() }

    then:
    semaphore.available() == 0

    when:
    semaphore.reset()

    then:
    semaphore.available() == permitCount

    where:
    permitCount | _
    1           | _
    2           | _
  }

  void 'unlimited semaphore is always available'() {
    given:
    final int threads = 100
    final semaphore = NonBlockingSemaphore.unlimited()

    when:
    final acquired = (1..threads).collect {
      semaphore.acquire()? 1 : 0
    }.collect { it }.sum()

    then:
    acquired == threads
    semaphore.available() == Integer.MAX_VALUE

    when:
    int availableAfterRelease = semaphore.release()

    then:
    availableAfterRelease == Integer.MAX_VALUE
    semaphore.available() == Integer.MAX_VALUE

    when:
    semaphore.reset()

    then:
    semaphore.available() == Integer.MAX_VALUE
  }

  void 'cannot create a semaphore without at least 1 permit'() {
    when:
    NonBlockingSemaphore.withPermitCount(0)

    then:
    thrown(AssertionError)

    when:
    NonBlockingSemaphore.withPermitCount(-1)

    then:
    thrown(AssertionError)
  }
}
