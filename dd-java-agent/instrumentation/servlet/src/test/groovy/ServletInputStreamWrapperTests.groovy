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

  ServletInputStreamWrapper inputStream

  @Test
  void 'forwards several input stream methods'() {
    storedByteBody = new StoredByteBody(listener, Charset.forName('UTF-8'), 0)
    ServletInputStream mockIs = Mock()

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
    storedByteBody.get() == '\u0000Hello'
  }


  void 'the input stream observes the limit of cached data'() {
    storedByteBody = new StoredByteBody(listener, Charset.forName('ISO-8859-1'), 0)
    ServletInputStream mockIs = Mock()

    setup:
    inputStream = new ServletInputStreamWrapper(mockIs, storedByteBody)

    when:
    assert inputStream.read(new byte[1024 * 1024 - 1]) == 1024 * 1024 - 1
    assert inputStream.read(new byte[300]) == 100
    assert inputStream.read() == ('2' as char) as int

    then:
    1 * mockIs.read(_ as byte[]) >> 1024 * 1024 - 1 // 1 short of limit
    1 * listener.onBodyStart(_)

    then:
    1 * mockIs.read(_ as byte[]) >> {
      it[0][0] = (('1' as char) as byte)
      it[0][1] = (('2' as char) as byte) // ignored
      100
    }
    then:
    1 * mockIs.read() >> (('2' as char) as int) // ignored

    def body = storedByteBody.get()
    body[1024 * 1024 - 2] == '\u0000'
    body[1024 * 1024 - 1] == '1'
    body.length() == 1024 * 1024
  }

}
