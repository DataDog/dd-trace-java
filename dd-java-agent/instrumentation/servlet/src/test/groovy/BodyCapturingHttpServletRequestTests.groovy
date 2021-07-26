import datadog.trace.api.http.StoredBodyListener
import datadog.trace.instrumentation.servlet.http.common.BodyCapturingHttpServletRequest
import org.junit.Test
import spock.lang.Specification

import javax.servlet.ServletInputStream
import javax.servlet.http.HttpServletRequest
import java.nio.CharBuffer

class BodyCapturingHttpServletRequestTests extends Specification {

  HttpServletRequest mockReq = Mock()
  StoredBodyListener listener = Mock()

  BodyCapturingHttpServletRequest testee =
  new BodyCapturingHttpServletRequest(mockReq, listener)

  @Test
  void 'intercepts the input stream'() {
    ServletInputStream mockIs = Mock()
    ServletInputStream inputStream

    when:
    inputStream = testee.inputStream

    then:
    1 * mockReq.characterEncoding >> 'UTF-8'
    1 * mockReq.inputStream >> mockIs

    when:
    assert inputStream instanceof BodyCapturingHttpServletRequest.ServletInputStreamWrapper
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
    testee.get() == '\u0000Hello'
  }

  void 'intercepts the reader stream'() {
    BufferedReader reader
    BufferedReader mockReader = Mock()

    when:
    reader = testee.reader
    assert reader instanceof BodyCapturingHttpServletRequest.BufferedReaderWrapper

    then:
    1 * mockReq.reader >> mockReader

    when:
    assert reader.read() == ('H' as char) as int
    def chars = new char[2]
    assert reader.read(chars) == 2
    assert chars[0] == 'e'
    assert chars[1] == 'l'

    chars = new char[4]
    assert reader.read(chars, 1, 3) == 2
    assert chars[1] == 'l'
    assert chars[2] == 'o'

    CharBuffer buffer = CharBuffer.allocate(6)
    assert reader.read(buffer) == 6
    assert reader.readLine() == '!'
    assert reader.readLine() == null
    reader.close()

    then:
    1 * mockReader.read() >> (('H' as char) as int)
    then:
    1 * listener.onBodyStart(_)
    then:
    1 * mockReader.read(_ as char[]) >> {
      it[0][0] = 'e' as char
      it[0][1] = 'l' as char
      2
    }
    then:
    1 * mockReader.read(_ as char[], _, _) >> {
      it[0][1] = 'l' as char
      it[0][2] = 'o' as char
      2
    }
    then:
    1 * mockReader.read(_ as CharBuffer) >> {
      CharBuffer buffer2 = it[0]
      buffer2.append(' world')
      6
    }
    then:
    2 * mockReader.readLine() >>> ['!', null]
    then:
    1 * listener.onBodyEnd(_)

    testee.get() == 'Hello world!\n'
  }

  void 'the input stream observes the limit of cached data'() {
    ServletInputStream mockIs = Mock()
    ServletInputStream inputStream

    when:
    inputStream = testee.inputStream
    then:
    1 * mockReq.characterEncoding >> 'ISO-8859-1'
    1 * mockReq.inputStream >> mockIs

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

    def body = testee.get()
    body[1024 * 1024 - 2] == '\u0000'
    body[1024 * 1024 - 1] == '1'
    body.length() == 1024 * 1024
  }

  void 'the buffered reader observes the limit of cached data'() {
    BufferedReader reader
    BufferedReader mockReader = Mock()

    when:
    reader = testee.reader

    then:
    1 * mockReq.reader >> mockReader

    when:
    assert reader.read(new char[1024 * 1024 - 1]) == 1024 * 1024 -1
    assert reader.read(new char[300]) == 100
    assert reader.read() == ('2' as char) as int
    reader.close()

    then:
    1 * mockReader.read(_ as char[]) >> 1024 * 1024 -1 // 1 short of limit
    1 * listener.onBodyStart(_)
    then:
    1 * mockReader.read(_ as char[]) >> {
      it[0][0] = '1' as char
      it[0][1] = '2' as char // ignored
      100
    }
    then:
    1 * mockReader.read() >> (('2' as char) as int) // ignored
    1 * listener.onBodyEnd(_)
    1 * mockReader.close()


    def body = testee.get()
    body[1024 * 1024 - 2] == '\u0000'
    body[1024 * 1024 - 1] == '1'
    assert body.length() == 1024 * 1024
  }
}
