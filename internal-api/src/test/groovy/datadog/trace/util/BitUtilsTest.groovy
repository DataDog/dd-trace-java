package datadog.trace.util

import static datadog.trace.util.BitUtils.nextPowerOfTwo

import datadog.trace.test.util.DDSpecification

class BitUtilsTest extends DDSpecification {
  def "nextPowerOfTwo(#input) should return #expected"() {
    expect:
    nextPowerOfTwo(input) == expected

    where:
    input             | expected
    0                 | 1        // smallest case
    1                 | 1        // already power of two
    2                 | 2
    3                 | 4
    4                 | 4
    5                 | 8
    6                 | 8
    7                 | 8
    8                 | 8
    9                 | 16
    15                | 16
    16                | 16
    17                | 32
    31                | 32
    32                | 32
    33                | 64
    63                | 64
    64                | 64
    65                | 128
    1000              | 1024
    1023              | 1024
    1024              | 1024
    1025              | 2048
    4096              | 4096
    4097              | 8192
    -1                | 1        // negative input edge case
    Integer.MAX_VALUE | (1 << 30) // largest safe power of two
  }
}
