package datadog.trace.api.http

import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.RequestContext
import spock.lang.Specification

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.util.function.BiFunction

class StoredByteBodyTest extends Specification {
  RequestContext requestContext = Mock()
  BiFunction<RequestContext, StoredBodySupplier, Void> startCb = Mock()
  BiFunction<RequestContext, StoredBodySupplier, Flow<Void>> endCb = Mock()

  StoredByteBody storedByteBody

  void 'basic test with no buffer extension'() {
    storedByteBody = new StoredByteBody(requestContext, startCb, endCb, null, 0)
    Flow mockFlow = Mock()

    when:
    storedByteBody.appendData((int) 'a') // not "as int"

    then:
    1 * startCb.apply(requestContext, storedByteBody)

    when:
    storedByteBody.appendData([(int)'a']* 127 as byte[], 0, 127)
    def flow = storedByteBody.maybeNotify()

    then:
    1 * endCb.apply(requestContext, storedByteBody) >> mockFlow
    storedByteBody.get() as String == 'a' * 128
    flow.is(mockFlow)
  }

  void 'test store limit'() {
    storedByteBody = new StoredByteBody(requestContext, startCb, endCb, null, 0)

    when:
    storedByteBody.appendData((int) 'a')

    then:
    1 * startCb.apply(requestContext, storedByteBody)

    when:
    // last byte ignored
    storedByteBody.appendData([(int)'a']* 128 * 1024 as byte[], 0, 128 * 1024)
    // ignored
    storedByteBody.appendData(0)
    // ignored
    storedByteBody.appendData([0] as byte[], 0, 1)

    then:
    storedByteBody.get() as String == 'a' * (128 * 1024)
  }

  void 'ignores invalid integers given to appendData'() {
    storedByteBody = new StoredByteBody(requestContext, startCb, endCb, null, 0)

    when:
    storedByteBody.appendData(-1)
    storedByteBody.appendData(256)

    then:
    storedByteBody.get() as String == ''
  }

  void 'well formed utf8 data'() {
    storedByteBody = new StoredByteBody(requestContext, startCb, endCb, null, 0)

    when:
    def data = '\u00E1\u0800\uD800\uDC00'.getBytes(Charset.forName('UTF-8'))
    storedByteBody.appendData(data, 0, data.length)

    then:
    storedByteBody.get() as String == '\u00E1\u0800\uD800\uDC00'
  }

  void 'non UTF8 data with specified encoding'() {
    storedByteBody = new StoredByteBody(requestContext, startCb, endCb, Charset.forName('ISO-8859-1'), 0)

    when:
    def data = 'á'.getBytes(Charset.forName('UTF-8'))
    storedByteBody.appendData(data, 0, data.length)

    then:
    storedByteBody.get() as String == '\u00C3\u00A1'
  }

  void 'fallback to latin1 on first byte'() {
    storedByteBody = new StoredByteBody(requestContext, startCb, endCb, null, 0)

    when:
    storedByteBody.appendData([0xFF] as byte[], 0, 1)

    then:
    storedByteBody.get() as String == '\u00FF'
  }

  void 'fallback to latin1 on second byte'() {
    storedByteBody = new StoredByteBody(requestContext, startCb, endCb, null, 0)

    when:
    storedByteBody.appendData([0xC3, 0xC3] as byte[], 0, 2)
    then:
    storedByteBody.get() as String == '\u00C3\u00C3'
  }

  void 'fallback to latin1 on third byte'() {
    storedByteBody = new StoredByteBody(requestContext, startCb, endCb, null, 0)

    when:
    storedByteBody.appendData([0xE0, 0x80, 0x7F] as byte[], 0, 3)
    then:
    storedByteBody.get() as String == '\u00E0\u0080\u007F'
  }

  void 'fallback to latin1 on fourth byte'() {
    storedByteBody = new StoredByteBody(requestContext, startCb, endCb, null, 0)

    when:
    storedByteBody.appendData([0xF0, 0x80, 0x80, 0x7F] as byte[], 0, 4)
    then:
    storedByteBody.get() as String == '\u00F0\u0080\u0080\u007F'
  }

  void 'fallback to latin on unfinished 2 byte sequences'() {
    storedByteBody = new StoredByteBody(requestContext, startCb, endCb, null, 0)

    when:
    storedByteBody.appendData(0xC3)
    storedByteBody.maybeNotify()

    then:
    storedByteBody.get() as String == '\u00C3'
  }

  void 'fallback to latin on unfinished 3 byte sequences'() {
    storedByteBody = new StoredByteBody(requestContext, startCb, endCb, null, 0)

    when:
    storedByteBody.appendData([0xE0, 0xA0] as byte[], 0, 2)
    storedByteBody.maybeNotify()

    then:
    storedByteBody.get() as String == '\u00E0\u00A0'
  }

  void 'fallback to latin on unfinished 4 byte sequences'() {
    storedByteBody = new StoredByteBody(requestContext, startCb, endCb, null, 0)

    when:
    storedByteBody.appendData([0xF0, 0x90, 0x80] as byte[], 0, 3)
    storedByteBody.maybeNotify()

    then:
    storedByteBody.get() as String == '\u00F0\u0090\u0080'
  }

  void 'utf-8 data can be reencoded as latin1'() {
    storedByteBody = new StoredByteBody(requestContext, startCb, endCb, null, 0)
    def bytes = ("á" * 16).getBytes(Charset.forName('UTF-8'))

    when:
    storedByteBody.appendData(bytes, 0, bytes.length)

    then:
    storedByteBody.get() as String == 'á' * 16

    when:
    2.times { storedByteBody.appendData(0x80) }

    then:
    storedByteBody.get() as String == '\u00C3\u00A1' * 16 + "\u0080\u0080"
  }

  void 'bytebuffer append variant'() {
    storedByteBody = new StoredByteBody(requestContext, startCb, endCb, null, 0)
    StoredByteBody.ByteBufferWriteCallback mockCb = Mock()
    def iteration = 1

    when:
    storedByteBody.appendData(mockCb, 68)

    then:
    mockCb.put(_) >> {
      ByteBuffer bb = it[0]
      if (iteration++ == 1) {
        assert bb.remaining() == 64
        bb.put(('a' * 64).getBytes('ISO-8859-1'))
      } else {
        assert bb.remaining() == 4
        bb.put('0123'.getBytes('ISO-8859-1'))
      }
    }
    storedByteBody.get() as String == ('a' * 64) + '0123'
  }

  void 'byte append variant is skipped after store limit'() {
    storedByteBody = new StoredByteBody(requestContext, startCb, endCb, null, 0)
    StoredByteBody.ByteBufferWriteCallback mockCb = Mock()

    when:
    storedByteBody.appendData([(int)'a']* 128 * 1024 as byte[], 0, 128 * 1024)
    storedByteBody.get() // force commit
    storedByteBody.appendData(mockCb, 1)

    then:
    0 * mockCb._(*_)
  }
}
