package datadog.trace.instrumentation.tomcat;

import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_REQUEST_HEADERS_X_DATADOG_ENDPOINT_SCAN;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_REQUEST_HEADERS_X_DATADOG_SECURITY_TEST;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import org.apache.coyote.Request;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.http.MimeHeaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TomcatSecurityTestingHeadersTest {
  private final AgentSpan span = mock(AgentSpan.class);
  private final Request request = new Request();
  private final MimeHeaders headers = request.getMimeHeaders();

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void tagsFirstNonNullMarkersWithoutReadingUnrelatedValues(boolean bytes) {
    add("cookie", "unrelated", bytes);
    add("X-Datadog-Endpoint-Scan", null, bytes);
    add("X-Datadog-Endpoint-Scan", "scan", bytes);
    add("x-datadog-endpoint-scan", "ignored", bytes);
    add("X-DATADOG-SECURITY-TEST", "", bytes);
    add("x-datadog-security-test", "ignored", bytes);

    if (bytes) {
      forbidDecoding(0);
      forbidDecoding(3);
      forbidDecoding(5);
    }
    tag();

    verify(span).setTag(HTTP_REQUEST_HEADERS_X_DATADOG_ENDPOINT_SCAN, "scan");
    verify(span).setTag(HTTP_REQUEST_HEADERS_X_DATADOG_SECURITY_TEST, "");
    verifyNoMoreInteractions(span);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void missingMarkersDoNotReadAnyHeaderValues(boolean bytes) {
    add("cookie", "session=abc", bytes);
    add("authorization", "Bearer token", bytes);
    if (bytes) {
      forbidDecoding(0);
      forbidDecoding(1);
    }
    tag();
    verifyNoMoreInteractions(span);
  }

  @Test
  void tagsSingleMarker() {
    add("x-datadog-security-test", "test", true);
    tag();
    verify(span).setTag(HTTP_REQUEST_HEADERS_X_DATADOG_SECURITY_TEST, "test");
    verifyNoMoreInteractions(span);
  }

  @Test
  void ignoresNullMarkerValues() {
    add("x-datadog-endpoint-scan", null, true);
    add("x-datadog-security-test", null, true);
    tag();
    verifyNoMoreInteractions(span);
  }

  @Test
  void toleratesMissingCarrierAndEmptyHeaders() {
    TomcatDecorator.DECORATE.tagSecurityTestingHeaders(span, null);
    TomcatDecorator.DECORATE.tagSecurityTestingHeaders(span, request);
    verifyNoMoreInteractions(span);
  }

  private void tag() {
    TomcatDecorator.DECORATE.tagSecurityTestingHeaders(span, request);
  }

  private void forbidDecoding(int index) {
    headers
        .getValue(index)
        .getByteChunk()
        .setCharset(
            new Charset("unexpected-decoding", null) {
              @Override
              public boolean contains(Charset charset) {
                return false;
              }

              @Override
              public CharsetDecoder newDecoder() {
                throw new AssertionError("Unrelated or duplicate value decoded");
              }

              @Override
              public CharsetEncoder newEncoder() {
                throw new AssertionError("Unexpected encoding");
              }
            });
  }

  private void add(String name, String value, boolean bytes) {
    MessageBytes field;
    if (bytes) {
      byte[] encoded = name.getBytes(StandardCharsets.ISO_8859_1);
      field = headers.addValue(encoded, 0, encoded.length);
      if (value != null) {
        encoded = value.getBytes(StandardCharsets.ISO_8859_1);
        field.setBytes(encoded, 0, encoded.length);
      }
    } else {
      field = headers.addValue(name);
      if (value != null) {
        field.setString(value);
      }
    }
  }
}
