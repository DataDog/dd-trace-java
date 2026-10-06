package datadog.trace.bootstrap.instrumentation.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.DDTags;
import datadog.trace.api.KnownTags;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class OtelHttpSemanticsTest {

  @ParameterizedTest
  @ValueSource(strings = {"GET", "POST", "DELETE", "PATCH", "QUERY", "CONNECT"})
  void setRequestMethodKeepsKnownMethods(String method) {
    AgentSpan span = mock(AgentSpan.class);

    OtelHttpSemantics.setRequestMethod(span, method);

    verify(span).setTag(KnownTags.HTTP_METHOD_OTEL_NAME, method);
    verify(span, never()).setTag(eq(KnownTags.HTTP_REQUEST_METHOD_ORIGINAL_NAME), anyString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"PROPFIND", "get", "Get", "BOGUS"})
  void setRequestMethodNormalizesUnknownMethodsToOther(String method) {
    AgentSpan span = mock(AgentSpan.class);

    OtelHttpSemantics.setRequestMethod(span, method);

    verify(span).setTag(KnownTags.HTTP_METHOD_OTEL_NAME, OtelHttpSemantics.OTHER_METHOD);
    verify(span).setTag(KnownTags.HTTP_REQUEST_METHOD_ORIGINAL_NAME, method);
  }

  @ParameterizedTest
  @CsvSource({"GET,GET", "POST,POST", "QUERY,QUERY", "PROPFIND,HTTP", "get,HTTP"})
  void spanNameMethodUsesNormalizedMethod(String method, String expected) {
    assertEquals(expected, OtelHttpSemantics.spanNameMethod(method));
  }

  @Test
  void spanNameMethodUsesHttpForNullMethod() {
    assertEquals("HTTP", OtelHttpSemantics.spanNameMethod(null));
  }

  @ParameterizedTest
  @CsvSource({
    "http://host:8080/p, http://host:8080/p",
    "http://user:pass@host/p, http://REDACTED:REDACTED@host/p",
    "http://user@host/p, http://REDACTED@host/p",
    "https://u:p@host:443/a?b=c, https://REDACTED:REDACTED@host:443/a?b=c",
    "https://u:p@host/u:p@path, https://REDACTED:REDACTED@host/u:p@path"
  })
  void redactedUrlRedactsCredentials(String raw, String expected) {
    assertEquals(expected, OtelHttpSemantics.redactedUrl(URI.create(raw)));
  }

  @ParameterizedTest
  @CsvSource({
    "http://host/p?token=secret, http://host/p",
    "http://host/p#frag, http://host/p#frag",
    "http://host/p?q=1#frag, http://host/p#frag",
    "http://host/p#frag?q=1, http://host/p#frag?q=1",
    "http://host/p, http://host/p"
  })
  void withoutQueryPreservesFragment(String url, String expected) {
    assertEquals(expected, OtelHttpSemantics.withoutQuery(url));
  }

  @ParameterizedTest
  @CsvSource({
    "http://host:8080/, 8080",
    "http://host/, 80",
    "https://host/, 443",
    "ftp://host/, -1"
  })
  void serverPortFallsBackToSchemeDefault(String url, int expectedPort) {
    assertEquals(expectedPort, OtelHttpSemantics.serverPort(URI.create(url)));
  }

  @Test
  void setErrorTypeSetsStatusWhenAbsent() {
    AgentSpan span = mock(AgentSpan.class);
    when(span.getTag(DDTags.ERROR_TYPE)).thenReturn(null);

    OtelHttpSemantics.setErrorType(span, 500);

    verify(span).setTag(DDTags.ERROR_TYPE, "500");
  }

  @Test
  void setErrorTypeDoesNotOverrideExistingErrorType() {
    AgentSpan span = mock(AgentSpan.class);
    when(span.getTag(DDTags.ERROR_TYPE)).thenReturn("java.net.UnknownHostException");

    OtelHttpSemantics.setErrorType(span, 500);

    verify(span, never()).setTag(eq(DDTags.ERROR_TYPE), any(String.class));
  }
}
