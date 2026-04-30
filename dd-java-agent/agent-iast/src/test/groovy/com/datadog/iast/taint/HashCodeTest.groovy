package com.datadog.iast.taint

import datadog.trace.test.util.DDSpecification
import groovy.transform.CompileStatic
import spock.lang.IgnoreIf
import spock.lang.Shared

/**
 * Test our assumptions about identity hash codes in tested JVMs.
 */
class HashCodeTest extends DDSpecification {

  @Shared
  private static final int N_HASHES = 1 << 16

  @Shared
  private static final List<Integer> HASH_CODES = genHashCodes(N_HASHES)

  @IgnoreIf({ !System.getProperty("java.vm.name").contains("OpenJDK") })
  def 'Identity hash codes are always positive in OpenJDK'() {
    expect:
    HASH_CODES.every {
      it > 0
    }
  }

  @IgnoreIf({ !System.getProperty("java.vm.name").contains("OpenJ9") })
  def 'Identity hash can be negative on OpenJ9'() {
    expect:
    HASH_CODES.any {
      it < 0
    }
  }

  def 'Identity hash codes are uniformly distributed'() {
    setup:
    final nBuckets = 64
    final expectedPerBucket = N_HASHES / nBuckets
    final tolerance = 0.2

    when:
    final positiveHashCodes = HASH_CODES.collect {
      it & Integer.MAX_VALUE
    }
    final buckets = positiveHashCodes.groupBy { it % nBuckets }
    final nPerBucket = buckets.collect { it.getValue().size() }

    then:
    nPerBucket.each {
      assert it <= expectedPerBucket * (1 + tolerance)
      assert it >= expectedPerBucket * (1 - tolerance)
    }
  }

  @CompileStatic
  private static List<Integer> genHashCodes(final int n) {
    (1..n).collect {
      System.identityHashCode(Double.toString(Math.random()))
    }
  }
}
