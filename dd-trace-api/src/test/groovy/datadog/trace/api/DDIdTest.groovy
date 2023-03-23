package datadog.trace.api

import spock.lang.Specification

class DDIdTest extends Specification {
  def "test hexadecimal String representations #highOrderBits | #lowOrderBits [#size]"() {
    when:
    def highOrder = highOrderSize == 0 ? "" : DDId.toHexStringPadded(highOrderBits, highOrderSize)
    def lowOrder = DDId.toHexStringPadded(lowOrderBits, lowOrderSize)
    def lowOrderOnly = DDId.toHexStringPadded(lowOrderBits, size)

    then:
    DDId.toHexStringPadded(highOrderBits, lowOrderBits, size) == highOrder + lowOrder
    DDId.toHexStringPadded(0L, lowOrderBits, size) == lowOrderOnly

    where:
    highOrderBits        | lowOrderBits         | size
    0L                   | 0L                   | 10
    0L                   | 0L                   | 16
    0L                   | 0L                   | 20
    0L                   | 0L                   | 32
    0L                   | 0L                   | 40
    1L                   | 2L                   | 10
    1L                   | 2L                   | 16
    1L                   | 2L                   | 20
    1L                   | 2L                   | 32
    1L                   | 2L                   | 40
    6536977903480360123L | 3270264562721133536L | 10
    6536977903480360123L | 3270264562721133536L | 16
    6536977903480360123L | 3270264562721133536L | 20
    6536977903480360123L | 3270264562721133536L | 32
    6536977903480360123L | 3270264562721133536L | 40

    highOrderUsed = size > 16
    highOrderSize = Math.min(16, Math.max(0, size - 16))
    lowOrderSize = Math.min(16, size)
  }
}
