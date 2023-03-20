package datadog.trace.api

import datadog.trace.test.util.DDSpecification
import spock.lang.Ignore

@Ignore("Should be tested using TraceIdWithOriginal")
class DDTraceIdOldTest extends DDSpecification {


  def "convert ids from/to hex String and truncate to 64 bits while keeping the original"() {
    // TODO 128b support
    when:
    DDTraceId ddid = null
    try {
      ddid = DDTraceId.fromHexTruncatedWithOriginal(hexId)
    } catch (NumberFormatException ignored) {
    }

    then:
    if (expectedId) {
      assert ddid == expectedId
      assert ddid.toHexStringOrOriginal() == hexId
    } else {
      assert !ddid
    }

    where:
    hexId                          | expectedId
    null                           | null
    "000"                          | DD64bTraceId.ZERO
    "0001"                         | DD64bTraceId.ONE
    "f" * 16                       | DD64bTraceId.MAX
    "7" + "f" * 15                 | DD64bTraceId.from(Long.MAX_VALUE)
    "8" + "0" * 15                 | DD64bTraceId.from(Long.MIN_VALUE)
    "0" * 4 + "8" + "0" * 15       | DD64bTraceId.from(Long.MIN_VALUE)
    "1" * 8 + "0" * 8 + "cafebabe" | DD64bTraceId.from(3405691582)
    "1" * 12 + "0123456789abcdef"  | DD64bTraceId.from(81985529216486895)
  }

  def "convert ids from/to part of hex String and truncate to 64 bits while keeping the original part"() {  // TODO 128b support?
    when:
    DDTraceId ddid = null
    try {
      ddid = DDTraceId.fromHexTruncatedWithOriginal(hexId, start, length, lcOnly)
    } catch (NumberFormatException ignored) {
    }

    then:
    if (expectedId) {
      assert ddid == expectedId
      assert ddid.toHexStringOrOriginal() == expectedHex
      assert ddid.toHexStringPaddedOrOriginal(16) == padHex(16, expectedHex)
      assert ddid.toHexStringPaddedOrOriginal(32) == padHex(32, expectedHex)
    } else {
      assert !ddid
    }

    where:
    hexId                          | start| length | lcOnly | expectedHex | expectedId
    null                           |  1   |  1     | false  | null        | null
    ""                             |  1   |  1     | false  | null        | null
    "00"                           | -1   |  1     | false  | null        | null
    "00"                           |  0   |  0     | false  | null        | null
    "00"                           |  1   |  1     | false  | "0"         | DD64bTraceId.ZERO
    "0001"                         |  2   |  2     | false  | "01"        | DD64bTraceId.ONE
    "f" * 16                       |  0   |  16    | true   | "f" * 16    | DD64bTraceId.MAX
    "f" * 12 + "Ffff"              |  0   |  16    | true   | null        | null
    "fFff" + ("f" * 16)            |  0   |  20    | true   | null        | null
    "Cafe" + ("f" * 16) + "F00d"   |  4   |  16    | false  | "f" * 16    | DD64bTraceId.MAX
  }

  static padHex(int size, String hex) {
    def len = hex.length()
    if (len >= size) {
      return hex
    }
    return "${"0" * (size - len)}$hex"
  }
}
