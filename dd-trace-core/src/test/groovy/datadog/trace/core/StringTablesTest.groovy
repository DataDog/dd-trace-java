package datadog.trace.core

import datadog.trace.util.test.DDSpecification

class StringTablesTest extends DDSpecification {

  def "naive string keys table strategy interns a small number of strings"() {
    // when this starts failing, it's time to investigate better ways to precompute UTF-8 encodings
    expect:
    StringTables.UTF8_INTERN_KEYS_TABLE.size() < 256
  }

  def "naive string tags table strategy interns a small number of strings"() {
    // when this starts failing, it's time to investigate better ways to precompute UTF-8 encodings
    expect:
    StringTables.UTF8_INTERN_TAGS_TABLE.size() < 256
  }

  def "naive string keys table strategy interns has low spatial overhead"() {
    int approxSizeBytesIgnoringHashMapOverhead = 0
    for (Map.Entry<String, byte[]> entry : StringTables.UTF8_INTERN_KEYS_TABLE.entrySet()) {
      // include the string even though it's constant, because the strategy
      // is naive enough to include everything we *could* need, some of these
      // constants might not otherwise ever be loaded
      approxSizeBytesIgnoringHashMapOverhead += entry.getKey().length()
      approxSizeBytesIgnoringHashMapOverhead += entry.getValue().length
    }
    // less than 4KB memory overhead seems reasonable for reducing GBs of allocations
    expect:
    approxSizeBytesIgnoringHashMapOverhead < 1024 * 4
  }

  def "naive string tags table strategy interns has low spatial overhead"() {
    int approxSizeBytesIgnoringHashMapOverhead = 0
    for (Map.Entry<String, byte[]> entry : StringTables.UTF8_INTERN_TAGS_TABLE.entrySet()) {
      // include the string even though it's constant, because the strategy
      // is naive enough to include everything we *could* need, some of these
      // constants might not otherwise ever be loaded
      approxSizeBytesIgnoringHashMapOverhead += entry.getKey().length()
      approxSizeBytesIgnoringHashMapOverhead += entry.getValue().length
    }
    // less than 2KB memory overhead seems reasonable for reducing some allocation
    // quite often, but we will often not find a value in this table so it's better
    // this stays small
    expect:
    approxSizeBytesIgnoringHashMapOverhead < 2 * 1024
  }
}
