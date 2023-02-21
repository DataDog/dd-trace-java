package datadog.trace.api

import datadog.trace.test.util.DDSpecification

class DictionaryTest extends DDSpecification {

  def "test idempotent"() {
    setup:
    Dictionary dictionary = new Dictionary()
    def key = "key"

    when: "insert same key second time"
    int expected = dictionary.encode(key)

    then: "we get the same encoding twice"
    dictionary.encode(key) == expected

    when: "insert lots of keys into the table"
    for (int i = 0; i < 10000; i++) {
      dictionary.encode(key + i)
    }

    then: "we still get the same encoding for the first key in the table"
    dictionary.encode(key) == expected
  }

  def "test hash collision"() {
    setup:
    Dictionary dictionary = new Dictionary()
    def key1 = "Aa"
    def key2 = "BB"
    assert key1.hashCode() == key2.hashCode()
    def bitset = new BitSet()

    when: "insert different keys with the same hash code"
    int key1Encoding = dictionary.encode(key1)
    int key2Encoding = dictionary.encode(key2)
    bitset.set(key1Encoding)
    bitset.set(key2Encoding)
    then: "the encodings differ"
    key1Encoding != key2Encoding

    when: "insert many colliding pairs of keys"
    def numCollisionsAdded = 10000
    for (int i = 0; i < numCollisionsAdded; i++) {
      // hash codes also collide with each other
      int e1 = dictionary.encode(key1 + i)
      int e2 = dictionary.encode(key2 + i)
      bitset.set(e1)
      bitset.set(e2)
    }

    then: "we still get the same encodings for the first two keys"
    dictionary.encode(key1) == key1Encoding
    dictionary.encode(key2) == key2Encoding

    then: "no encodings are duplicated"
    bitset.cardinality() == 2 * (numCollisionsAdded + 1)
  }
}
