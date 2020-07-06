package datadog.trace.api

import com.google.common.io.BaseEncoding
import datadog.trace.util.test.DDSpecification

import java.nio.charset.StandardCharsets

class DDIdTest extends DDSpecification {
  def "convert ids from/to long and check strings and BigInteger"() {
    when:
    final ddid = DDId.from(longId)

    then:
    ddid == expectedId
    ddid.toLong() == longId
    ddid.toString() == expectedString
    ddid.toHexString() == expectedHex

    where:
    longId         | expectedId                | expectedString         | expectedHex
    0              | DDId.ZERO                 | "0"                    | "0"
    1              | DDId.ONE                  | "1"                    | "1"
    -1             | DDId.MAX                  | "18446744073709551615" | "f" * 16
    Long.MAX_VALUE | DDId.from(Long.MAX_VALUE) | "9223372036854775807"  | "7" + "f" * 15
    Long.MIN_VALUE | DDId.from(Long.MIN_VALUE) | "9223372036854775808"  | "8" + "0" * 15
  }

  def "convert ids from/to String"() {
    when:
    final ddid = DDId.from(stringId)

    then:
    ddid == expectedId
    ddid.toString() == stringId

    where:
    stringId                                        | expectedId
    "0"                                             | DDId.ZERO
    "1"                                             | DDId.ONE
    "18446744073709551615"                          | DDId.MAX
    "${Long.MAX_VALUE}"                             | DDId.from(Long.MAX_VALUE)
    "${BigInteger.valueOf(Long.MAX_VALUE).plus(1)}" | DDId.from(Long.MIN_VALUE)
  }

  def "fail on illegal String"() {
    when:
    DDId.from(stringId)

    then:
    thrown NumberFormatException

    where:
    stringId << [ null, "", "-1", "18446744073709551616", "18446744073709551625", "184467440737095516150", "18446744073709551a1", "184467440737095511a" ]
  }

  def "convert ids from/to hex String"() {
    when:
    final ddid = DDId.fromHex(hexId)

    then:
    ddid == expectedId
    if (hexId.length() > 1) {
      hexId = hexId.replaceAll("^0+", "") // drop leading zeros
    }
    ddid.toHexString() == hexId

    where:
    hexId                    | expectedId
    "0"                      | DDId.ZERO
    "1"                      | DDId.ONE
    "f" * 16                 | DDId.MAX
    "7" + "f" * 15           | DDId.from(Long.MAX_VALUE)
    "8" + "0" * 15           | DDId.from(Long.MIN_VALUE)
    "0" * 4 + "8" + "0" * 15 | DDId.from(Long.MIN_VALUE)
  }

  def "fail on illegal hex String"() {
    when:
    DDId.fromHex(hexId)

    then:
    thrown NumberFormatException

    where:
    hexId << [null, "", "-1", "1" + "0" * 16, "f" * 14 + "zf", "f" * 15 + "z" ]
  }

  def "lenient to base64 encoded valid ids decimal"() {
    when:
    final ddid = DDId.from(BaseEncoding.base64().encode(stringId.getBytes(StandardCharsets.ISO_8859_1)))

    then:
    ddid == expectedId
    ddid.toString() == stringId

    where:
    stringId                                        | expectedId
    "0"                                             | DDId.ZERO
    "1"                                             | DDId.ONE
    "18446744073709551615"                          | DDId.MAX
    "${Long.MAX_VALUE}"                             | DDId.from(Long.MAX_VALUE)
    "${BigInteger.valueOf(Long.MAX_VALUE).plus(1)}" | DDId.from(Long.MIN_VALUE)
  }

  def "lenient to base64 encoded valid ids hex"() {
    when:
    final ddid = DDId.fromHex(BaseEncoding.base64().encode(hexId.getBytes(StandardCharsets.ISO_8859_1)))

    then:
    ddid == expectedId
    if (hexId.length() > 1) {
      hexId = hexId.replaceAll("^0+", "") // drop leading zeros
    }
    ddid.toHexString() == hexId

    where:
    hexId                    | expectedId
    "0"                      | DDId.ZERO
    "1"                      | DDId.ONE
    "f" * 16                 | DDId.MAX
    "7" + "f" * 15           | DDId.from(Long.MAX_VALUE)
    "8" + "0" * 15           | DDId.from(Long.MIN_VALUE)
    "0" * 4 + "8" + "0" * 15 | DDId.from(Long.MIN_VALUE)
  }

  def "pump up the coverage"() {
    when:
    final ddid = DDId.generate()

    then:
    !ddid.equals(null)
    !ddid.equals("foo")
    ddid != DDId.ZERO
    ddid.equals(ddid)
    ddid.hashCode() == (int) (ddid.toLong() ^ (ddid.toLong() >>> 32))
  }
}
