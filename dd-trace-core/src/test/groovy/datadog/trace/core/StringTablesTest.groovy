package datadog.trace.core

import datadog.trace.test.util.DDSpecification

class StringTablesTest extends DDSpecification {

  def "naive string keys table strategy interns a small number of strings"() {
    // when this starts failing, it's time to investigate better ways to precompute UTF-8 encodings
    expect:
    StringTables.UTF8_INTERN_KEYS_TABLE.size() < 256
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
    // less than 8KB memory overhead seems reasonable for reducing GBs of allocations
    expect:
    approxSizeBytesIgnoringHashMapOverhead < 1024 * 8
  }
}
