import datadog.trace.api.http.StoredBodyListener
import datadog.trace.api.http.StoredByteBody
import datadog.trace.instrumentation.servlet.http.ServletInputStreamWrapper
import org.junit.Test
import spock.lang.Specification

import javax.servlet.ServletInputStream
import java.nio.charset.Charset

class ServletInputStreamWrapperTests extends Specification {
  StoredBodyListener listener = Mock()
  StoredByteBody storedByteBody

  ServletInputStream mockIs = Mock()
  ServletInputStreamWrapper inputStream

  @Test
  void 'forwards several input stream methods'() {
    storedByteBody = new StoredByteBody(listener, Charset.forName('UTF-8'), 0)

    when:
    inputStream = new ServletInputStreamWrapper(mockIs, storedByteBody)
    assert inputStream.read() == 0
    assert inputStream.read() == ('H' as char) as int
    assert inputStream.read(new byte[2]) == 2
    assert inputStream.read(new byte[4], 1, 3) == 2
    inputStream.close()

    then:
    2 * mockIs.read() >>> [0, ('H' as char) as int]
    1 * listener.onBodyStart(_)
    then:
    1 * mockIs.read(_ as byte[]) >> {
      it[0][0] = ('e' as char) as byte
      it[0][1] = ('l' as char) as byte
      2
    }
    then:
    1 * mockIs.read(_ as byte[], _, _) >> {
      it[0][1] = ('l' as char) as byte
      it[0][2] = ('o' as char) as byte
      2
    }
    then:
    1 * mockIs.close()
    then:
    1 * listener.onBodyEnd(_)
    storedByteBody.get() as String == '\u0000Hello'
  }

  void 'the input stream observes the limit of cached data'() {
    storedByteBody = new StoredByteBody(listener, Charset.forName('ISO-8859-1'), 0)

    setup:
    inputStream = new ServletInputStreamWrapper(mockIs, storedByteBody)

    when:
    assert inputStream.read(new byte[128 * 1024 - 1]) == 128 * 1024 - 1
    assert inputStream.read(new byte[300]) == 100
    assert inputStream.read() == ('2' as char) as int

    then:
    1 * mockIs.read(_ as byte[]) >> 128 * 1024 - 1 // 1 short of limit
    1 * listener.onBodyStart(_)

    then:
    1 * mockIs.read(_ as byte[]) >> {
      it[0][0] = (('1' as char) as byte)
      it[0][1] = (('2' as char) as byte) // ignored
      100
    }
    then:
    1 * mockIs.read() >> (('2' as char) as int) // ignored

    def body = storedByteBody.get() as String
    body[128 * 1024 - 2] == '\u0000'
    body[128 * 1024 - 1] == '1'
    body.length() == 128 * 1024
  }

  void 'readLine is implemented'() {
    storedByteBody = new StoredByteBody(listener, null, 0)
    def read = new byte[2]

    setup:
    inputStream = new ServletInputStreamWrapper(mockIs, storedByteBody)

    when:
    inputStream.readLine(read, 0, 2)

    then:
    1 * mockIs.readLine({ it.is(read) }, 0, 2) >> {
      it[0][0] = (byte) 'a'
      it[0][1] = (byte) '\n'
      2
    }
    read[0] == (byte) 'a'
    read[1] == (byte) '\n'
    storedByteBody.get() as String == 'a\n'
  }

  void 'misc methods are forwarded'() {
    storedByteBody = new StoredByteBody(listener, null, 0)

    setup:
    inputStream = new ServletInputStreamWrapper(mockIs, storedByteBody)

    when:
    assert inputStream.skip(3) == 3
    assert inputStream.available() == 42
    inputStream.mark(42)
    assert inputStream.markSupported() == true
    inputStream.reset()

    then:
    1 * mockIs.skip(3) >> 3
    1 * mockIs.available() >> 42
    1 * mockIs.mark(42)
    1 * mockIs.markSupported() >> true
    1 * mockIs.reset()
  }

  void 'finish notification read returns eof variant 1'() {
    storedByteBody = new StoredByteBody(listener, null, 0)

    setup:
    inputStream = new ServletInputStreamWrapper(mockIs, storedByteBody)

    when:
    inputStream.read()

    then:
    1 * mockIs.read() >> -1
    1 * listener.onBodyStart(_)
    1 * listener.onBodyEnd(_)
  }

  void 'finish notification read returns eof variant 2'() {
    storedByteBody = new StoredByteBody(listener, null, 0)

    setup:
    inputStream = new ServletInputStreamWrapper(mockIs, storedByteBody)

    when:
    inputStream.read(new byte[1])

    then:
    1 * mockIs.read(_ as byte[]) >> -1
    1 * listener.onBodyStart(_)
    1 * listener.onBodyEnd(_)
  }

  void 'finish notification read returns eof variant 3'() {
    storedByteBody = new StoredByteBody(listener, null, 0)

    setup:
    inputStream = new ServletInputStreamWrapper(mockIs, storedByteBody)

    when:
    inputStream.read(new byte[1], 0, 1)

    then:
    1 * mockIs.read(_ as byte[], _, _) >> -1
    1 * listener.onBodyStart(_)
    1 * listener.onBodyEnd(_)
  }

  void 'finish notification readLine returns eof'() {
    storedByteBody = new StoredByteBody(listener, null, 0)

    setup:
    inputStream = new ServletInputStreamWrapper(mockIs, storedByteBody)

    when:
    inputStream.readLine(new byte[1], 0, 1)

    then:
    1 * mockIs.readLine(_ as byte[], _, _) >> -1
    1 * listener.onBodyStart(_)
    1 * listener.onBodyEnd(_)
  }
}
