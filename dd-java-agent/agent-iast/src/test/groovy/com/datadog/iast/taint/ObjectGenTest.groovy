package com.datadog.iast.taint

import spock.lang.Shared
import spock.lang.Specification


class ObjectGenTest extends Specification {

  @Shared
  def capacity = 128

  @Shared
  def objectGen = new ObjectGen(128)

  def 'genBucket'() {
    given:
    def n = 5

    when:
    def objects = objectGen.genBucket(n, ObjectGen.TRUE)

    then:
    objects.size() == n
    objects.toSet().size() == n
    objects.groupBy({ (System.identityHashCode(it) & Integer.MAX_VALUE) % capacity }).size() == 1
  }

  def 'genBuckets'() {
    given:
    def m = 10
    def n = 5

    when:
    def objects = objectGen.genBuckets(m, n)

    then:
    objects.size() == m
    for (def objLst : objects) {
      assert objLst.size() == n
      assert objLst.toSet().size() == n
      assert objLst.groupBy({ (System.identityHashCode(it) & Integer.MAX_VALUE) % capacity }).size() == 1
    }

    when: 'again'
    objects = objectGen.genBuckets(m, n)

    then:
    objects.size() == m
    for (def objLst : objects) {
      assert objLst.size() == n
      assert objLst.toSet().size() == n
      assert objLst.groupBy({ (System.identityHashCode(it) & Integer.MAX_VALUE) % capacity }).size() == 1
    }
  }
}
