package datadog.trace.civisibility.ipc

import spock.lang.Specification

class ByteUtilsTest extends Specification {

  def "test put and get long: #l"() {
    given:
    def bytes = new byte[8]

    when:
    ByteUtils.putLong(bytes, 0, l)
    def got = ByteUtils.getLong(bytes, 0)

    then:
    got == l

    where:
    l << [
      0L,
      1L,
      Integer.MAX_VALUE,
      Integer.MIN_VALUE,
      1L + Integer.MAX_VALUE,
      Long.MAX_VALUE,
      Long.MIN_VALUE
    ]
  }

  def "test put and get short: #s"() {
    given:
    def bytes = new byte[2]

    when:
    ByteUtils.putShort(bytes, 0, s)
    def got = ByteUtils.getShort(bytes, 0)

    then:
    got == s

    where:
    s << [(short) 0, (short) 0, (short) 255, (short) 256, Short.MAX_VALUE]
  }

  def "test put and get unsigned int"() {
    given:
    def bytes = new byte[2]

    when:
    ByteUtils.putShort(bytes, 0, (short) (Short.MAX_VALUE + 1))
    def got = (int) (ByteUtils.getShort(bytes, 0) & 0xFFFF)

    then:
    got == Short.MAX_VALUE + 1
  }
}
