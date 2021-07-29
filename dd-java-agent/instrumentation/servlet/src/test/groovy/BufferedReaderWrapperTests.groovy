import datadog.trace.api.http.StoredBodyListener
import datadog.trace.api.http.StoredCharBody
import datadog.trace.instrumentation.servlet.http.BufferedReaderWrapper
import spock.lang.Specification

import java.nio.CharBuffer

class BufferedReaderWrapperTests extends Specification {

  StoredBodyListener listener = Mock()
  StoredCharBody storedCharBody = new StoredCharBody(listener, 0)

  BufferedReader mockReader = Mock()
  BufferedReader reader = new BufferedReaderWrapper(mockReader, storedCharBody)

  void 'intercepts the reader stream'() {
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

    storedCharBody.get() == 'Hello world!\n'
  }


  void 'the buffered reader observes the limit of cached data'() {
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


    def body = storedCharBody.get()
    body[1024 * 1024 - 2] == '\u0000'
    body[1024 * 1024 - 1] == '1'
    assert body.length() == 1024 * 1024
  }
}
