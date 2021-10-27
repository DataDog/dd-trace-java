package datadog.cws.tls

import com.sun.jna.Native

import datadog.trace.api.DDId
import datadog.trace.test.util.DDSpecification

class ErpcTlsTest extends DDSpecification {
  def "register trace and span to tls"() {
    setup:
    DummyErpcTls tls = new DummyErpcTls(1000)

    when:
    DDId traceId = DDId.from(789L)
    DDId spanId = DDId.from(456L)
    tls.registerSpan(123, traceId, spanId)

    then:
    tls.getTraceId(123) == traceId
    tls.getSpanId(123) == spanId
    tls.getTraceId(222) ==  DDId.from(0L)
    tls.getSpanId(111) == DDId.from(0L)
  }

  def "register tls"(){
    when:
    DummyErpcTls tls = new DummyErpcTls(1000)

    then:
    tls.lastRequest.getOpCode() == tls.REGISTER_SPAN_TLS_OP
    tls.lastRequest.getDataPointer().getLong(0) == tls.TLS_FORMAT
    tls.lastRequest.getDataPointer().getLong(Native.LONG_SIZE) == 1000
    tls.lastRequest.getDataPointer().getPointer(Native.LONG_SIZE*2) == tls.getTlsPointer()
  }
}