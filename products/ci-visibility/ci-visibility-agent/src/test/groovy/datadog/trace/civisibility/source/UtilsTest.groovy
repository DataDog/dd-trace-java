package datadog.trace.civisibility.source


import spock.lang.Specification

class UtilsTest extends Specification {

  def "test gets class stream for #clazz"() {
    expect:
    Utils.getClassStream(clazz).withCloseable { stream ->
      def cafebabe = readMagicNumber(stream)
      cafebabe[0] == (byte) 0xCA
      cafebabe[1] == (byte) 0xFE
      cafebabe[2] == (byte) 0xBA
      cafebabe[3] == (byte) 0xBE
    }

    where:
    clazz << [UtilsTestClass, Object]
  }

  private byte[] readMagicNumber(InputStream stream) {
    def bytes = new byte[4]
    def totalRead = 0
    while (totalRead != bytes.length) {
      totalRead += stream.read(bytes, totalRead, bytes.length - totalRead)
    }
    bytes
  }

  private static final class UtilsTestClass {}
}
