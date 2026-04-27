package datadog.trace.api.http

import spock.lang.Specification

class MultipartContentDecoderTest extends Specification {

  void 'decodeBytes uses declared UTF-8 charset'() {
    given:
    def text = 'héllo wörld'
    byte[] bytes = text.getBytes('UTF-8')

    expect:
    MultipartContentDecoder.decodeBytes(bytes, bytes.length, 'text/plain; charset=UTF-8') == text
  }

  void 'decodeBytes falls back to UTF-8 when Content-Type has no charset'() {
    given:
    def text = 'hello world'
    byte[] bytes = text.getBytes('UTF-8')

    expect:
    MultipartContentDecoder.decodeBytes(bytes, bytes.length, 'text/plain') == text
  }

  void 'decodeBytes falls back to UTF-8 when Content-Type is null'() {
    given:
    def text = 'hello world'
    byte[] bytes = text.getBytes('UTF-8')

    expect:
    MultipartContentDecoder.decodeBytes(bytes, bytes.length, null) == text
  }

  void 'decodeBytes falls back to ISO-8859-1 when bytes are invalid for declared charset'() {
    given:
    // 0xE9 is 'é' in ISO-8859-1 but an invalid lone UTF-8 byte
    byte[] bytes = 'café'.getBytes('ISO-8859-1')

    expect:
    MultipartContentDecoder.decodeBytes(bytes, bytes.length, null) == 'café'
  }

  void 'decodeBytes uses declared ISO-8859-1 charset'() {
    given:
    def text = 'café'
    byte[] bytes = text.getBytes('ISO-8859-1')

    expect:
    MultipartContentDecoder.decodeBytes(bytes, bytes.length, 'text/plain; charset=ISO-8859-1') == text
  }

  void 'decodeBytes respects length parameter'() {
    given:
    byte[] bytes = 'hello world'.getBytes('UTF-8')

    expect:
    MultipartContentDecoder.decodeBytes(bytes, 5, null) == 'hello'
  }

  void 'decodeBytes returns empty string for zero length'() {
    expect:
    MultipartContentDecoder.decodeBytes(new byte[16], 0, null) == ''
  }

  void 'decodeBytes falls back to ISO-8859-1 when declared charset cannot decode the bytes'() {
    given:
    // bytes are ISO-8859-1 encoded but Content-Type explicitly declares UTF-8
    byte[] bytes = 'café'.getBytes('ISO-8859-1')

    expect:
    MultipartContentDecoder.decodeBytes(bytes, bytes.length, 'text/plain; charset=UTF-8') == 'café'
  }

  void 'extractCharset returns null for null contentType'() {
    expect:
    MultipartContentDecoder.extractCharset(null) == null
  }

  void 'extractCharset returns null for empty contentType'() {
    expect:
    MultipartContentDecoder.extractCharset('') == null
  }

  void 'extractCharset returns null for contentType without charset'() {
    expect:
    MultipartContentDecoder.extractCharset('text/plain') == null
    MultipartContentDecoder.extractCharset('image/jpeg') == null
    MultipartContentDecoder.extractCharset('application/octet-stream') == null
  }

  void 'extractCharset returns null for invalid charset name'() {
    expect:
    MultipartContentDecoder.extractCharset('text/plain; charset=NOTACHARSET') == null
  }

  void 'extractCharset extracts charset case-insensitively'() {
    expect:
    MultipartContentDecoder.extractCharset('text/plain; CHARSET=UTF-8').name() == 'UTF-8'
    MultipartContentDecoder.extractCharset('text/plain; Charset=UTF-8').name() == 'UTF-8'
    MultipartContentDecoder.extractCharset('text/plain; charset=utf-8').name() == 'UTF-8'
  }

  void 'extractCharset extracts charset from standard Content-Type'() {
    expect:
    MultipartContentDecoder.extractCharset('text/plain; charset=UTF-8').name() == 'UTF-8'
    MultipartContentDecoder.extractCharset('text/xml; charset=ISO-8859-1').name() == 'ISO-8859-1'
  }

  void 'extractCharset extracts charset when followed by additional parameters'() {
    expect:
    MultipartContentDecoder.extractCharset('text/plain; charset=UTF-8; boundary=something').name() == 'UTF-8'
  }
}
