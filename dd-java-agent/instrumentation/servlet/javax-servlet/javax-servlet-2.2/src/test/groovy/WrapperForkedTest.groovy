import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.http.StoredBodySupplier
import datadog.trace.api.http.StoredByteBody
import datadog.trace.api.http.StoredCharBody
import datadog.trace.instrumentation.servlet.BufferedReaderWrapper
import datadog.trace.instrumentation.servlet2.ServletInputStreamWrapper
import spock.lang.Specification

import javax.servlet.ServletInputStream
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.util.function.BiFunction

/* Forked to avoid having this test load boot classes into the system
 * classloader and then failing other tests that run under SpockRunner
 * (see datadog.trace.agent.test.SpockRunner#setupBootstrapClasspath()). */
class WrapperForkedTest extends Specification {
  RequestContext requestContext = Mock(RequestContext) {
    getData(RequestContextSlot.APPSEC) >> it
  }
  BiFunction<RequestContext, StoredBodySupplier, Void> startCb = Mock()
  BiFunction<RequestContext, StoredBodySupplier, Flow<Void>> endCb = Mock()

  StoredCharBody storedCharBody = new StoredCharBody(requestContext, startCb, endCb, 0)
  BufferedReader mockReader = Mock()
  BufferedReader reader = new BufferedReaderWrapper(mockReader, storedCharBody)

  StoredByteBody storedByteBody
  ServletInputStream mockIs = Mock()
  ServletInputStreamWrapper inputStream

  // BEGIN BufferedReaderWrapper tests

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
    1 * startCb.apply(_, _)
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
    1 * endCb.apply(_, _) >> Flow.ResultFlow.empty()

    storedCharBody.get() as String == 'Hello world!\n'
  }

  void 'the buffered reader observes the limit of cached data'() {
    when:
    assert reader.read(new char[128 * 1024 - 1]) == 128 * 1024 -1
    assert reader.read(new char[300]) == 100
    assert reader.read() == ('2' as char) as int
    reader.close()

    then:
    1 * mockReader.read(_ as char[]) >> 128 * 1024 -1 // 1 short of limit
    1 * startCb.apply(_, _)
    then:
    1 * mockReader.read(_ as char[]) >> {
      it[0][0] = '1' as char
      it[0][1] = '2' as char // ignored
      100
    }
    then:
    1 * mockReader.read() >> (('2' as char) as int) // ignored
    1 * endCb.apply(_, _) >> Flow.ResultFlow.empty()
    1 * mockReader.close()


    def body = storedCharBody.get() as String
    body[128 * 1024 - 2] == '\u0000'
    body[128 * 1024 - 1] == '1'
    assert body.length() == 128 * 1024
  }

  void 'notification issues when read returns eof variant 1'() {
    when:
    reader.read()

    then:
    1 * mockReader.read() >> -1
    1 * startCb.apply(_, _)
    1 * endCb.apply(_, _) >> Flow.ResultFlow.empty()
  }

  void 'notification issues when read returns eof variant 2'() {
    when:
    reader.read(new char[1], 0, 1)

    then:
    1 * mockReader.read(_ as char[], _, _) >> -1
    1 * startCb.apply(_, _)
    1 * endCb.apply(_, _) >> Flow.ResultFlow.empty()
  }

  void 'notification issues when read returns eof variant 3'() {
    when:
    reader.read(CharBuffer.allocate(1))

    then:
    1 * mockReader.read(_ as CharBuffer) >> -1
    1 * startCb.apply(_, _)
    1 * endCb.apply(_, _) >> Flow.ResultFlow.empty()
  }

  void 'notification issues when read returns eof variant 4'() {
    when:
    reader.read(new char[1])

    then:
    1 * mockReader.read(_ as char[]) >> -1
    1 * startCb.apply(_, _)
    1 * endCb.apply(_, _) >> Flow.ResultFlow.empty()
  }

  // BEGIN ServletInputStreamWrapper tests

  void 'forwards several input stream methods'() {
    storedByteBody = new StoredByteBody(requestContext, startCb, endCb, Charset.forName('UTF-8'), 0)

    when:
    inputStream = new ServletInputStreamWrapper(mockIs, storedByteBody)
    assert inputStream.read() == 0
    assert inputStream.read() == ('H' as char) as int
    assert inputStream.read(new byte[2]) == 2
    assert inputStream.read(new byte[4], 1, 3) == 2
    inputStream.close()

    then:
    2 * mockIs.read() >>> [0, ('H' as char) as int]
    1 * startCb.apply(_, _)
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
    1 * endCb.apply(_, _) >> Flow.ResultFlow.empty()
    storedByteBody.get() as String == '\u0000Hello'
  }

  void 'the input stream observes the limit of cached data'() {
    storedByteBody = new StoredByteBody(requestContext, startCb, endCb, Charset.forName('ISO-8859-1'), 0)

    setup:
    inputStream = new ServletInputStreamWrapper(mockIs, storedByteBody)

    when:
    assert inputStream.read(new byte[128 * 1024 - 1]) == 128 * 1024 - 1
    assert inputStream.read(new byte[300]) == 100
    assert inputStream.read() == ('2' as char) as int

    then:
    1 * mockIs.read(_ as byte[]) >> 128 * 1024 - 1 // 1 short of limit
    1 * startCb.apply(_, _)

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
    storedByteBody = new StoredByteBody(requestContext, startCb, endCb, null, 0)
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
    storedByteBody = new StoredByteBody(requestContext, startCb, endCb, null, 0)

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
    storedByteBody = new StoredByteBody(requestContext, startCb, endCb, null, 0)

    setup:
    inputStream = new ServletInputStreamWrapper(mockIs, storedByteBody)

    when:
    inputStream.read()

    then:
    1 * mockIs.read() >> -1
    1 * startCb.apply(_, _)
    1 * endCb.apply(_, _) >> Flow.ResultFlow.empty()
  }

  void 'finish notification read returns eof variant 2'() {
    storedByteBody = new StoredByteBody(requestContext, startCb, endCb, null, 0)

    setup:
    inputStream = new ServletInputStreamWrapper(mockIs, storedByteBody)

    when:
    inputStream.read(new byte[1])

    then:
    1 * mockIs.read(_ as byte[]) >> -1
    1 * startCb.apply(_, _)
    1 * endCb.apply(_, _) >> Flow.ResultFlow.empty()
  }

  void 'finish notification read returns eof variant 3'() {
    storedByteBody = new StoredByteBody(requestContext, startCb, endCb, null, 0)

    setup:
    inputStream = new ServletInputStreamWrapper(mockIs, storedByteBody)

    when:
    inputStream.read(new byte[1], 0, 1)

    then:
    1 * mockIs.read(_ as byte[], _, _) >> -1
    1 * startCb.apply(_, _)
    1 * endCb.apply(_, _) >> Flow.ResultFlow.empty()
  }

  void 'finish notification readLine returns eof'() {
    storedByteBody = new StoredByteBody(requestContext, startCb, endCb, null, 0)

    setup:
    inputStream = new ServletInputStreamWrapper(mockIs, storedByteBody)

    when:
    inputStream.readLine(new byte[1], 0, 1)

    then:
    1 * mockIs.readLine(_ as byte[], _, _) >> -1
    1 * startCb.apply(_, _)
    1 * endCb.apply(_, _) >> Flow.ResultFlow.empty()
  }
}
