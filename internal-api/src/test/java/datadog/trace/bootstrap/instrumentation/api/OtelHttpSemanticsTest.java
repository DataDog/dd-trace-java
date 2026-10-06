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
import org.tabletest.junit.TableTest;

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
  @CsvSource({"GET,GET", "PROPFIND,HTTP"})
  void setMethodResourceNameUsesNormalizedMethod(String method, String expected) {
    AgentSpan span = mock(AgentSpan.class);

    OtelHttpSemantics.setMethodResourceName(span, method);

    verify(span).setResourceName(expected, ResourceNamePriorities.HTTP_PATH_NORMALIZER);
  }

  @Test
  void setMethodResourceNameUsesHttpForNullMethod() {
    AgentSpan span = mock(AgentSpan.class);

    OtelHttpSemantics.setMethodResourceName(span, null);

    verify(span).setResourceName("HTTP", ResourceNamePriorities.HTTP_PATH_NORMALIZER);
  }

  @Test
  void setClientUrlStripsQueryAndRedactsCredentials() {
    AgentSpan span = mock(AgentSpan.class);

    OtelHttpSemantics.setClientUrl(span, URI.create("https://u:p@host/a?token=secret"));

    verify(span).setTag(KnownTags.HTTP_URL_OTEL_NAME, "https://REDACTED:REDACTED@host/a");
    verify(span).setTag(KnownTags.URL_QUERY_NAME, "token=secret");
  }

  @Test
  void setClientUrlOmitsQueryTagWhenAbsent() {
    AgentSpan span = mock(AgentSpan.class);

    OtelHttpSemantics.setClientUrl(span, URI.create("http://host/a"));

    verify(span).setTag(KnownTags.HTTP_URL_OTEL_NAME, "http://host/a");
    verify(span, never()).setTag(eq(KnownTags.URL_QUERY_NAME), anyString());
  }

  @TableTest({
    "Scenario              | Raw URL                             | Expected URL                                 ",
    "No credentials        | http://host:8080/p                  | http://host:8080/p                           ",
    "Username and password | http://user:pass@host/p             | http://REDACTED:REDACTED@host/p              ",
    "Username only         | http://user@host/p                  | http://REDACTED@host/p                       ",
    "Query and fragment    | https://u:p@host:443/a?b=c#frag     | https://REDACTED:REDACTED@host:443/a?b=c#frag",
    "Credentials in path   | https://u:p@host/u:p@path           | https://REDACTED:REDACTED@host/u:p@path      ",
    "Underscore host       | http://user:pass@foo_bar/p          | http://REDACTED:REDACTED@foo_bar/p           ",
    "Underscore username   | http://user_@host/p                 | http://REDACTED@host/p                       ",
    "Unicode host          | http://user:pass@例え.テスト/p        | http://REDACTED:REDACTED@例え.テスト/p     ",
    "Encoded credentials   | http://u%40ser:p%40ss@foo_bar/p     | http://REDACTED:REDACTED@foo_bar/p           ",
    "At sign in password   | http://user:p@ss@foo_bar/p          | http://REDACTED:REDACTED@foo_bar/p           ",
    "Empty username        | http://:pass@foo_bar/p              | http://REDACTED:REDACTED@foo_bar/p           ",
    "IPv6 host             | http://user:pass@[::1]:8080/p       | http://REDACTED:REDACTED@[::1]:8080/p        ",
    "IPv6 username only    | http://user@[::1]:8080/p            | http://REDACTED@[::1]:8080/p                 ",
    "Scheme relative URL   | //user:pass@foo_bar/p               | //REDACTED:REDACTED@foo_bar/p                ",
    "At signs outside host | http://foo_bar/u:p@path?q=u:p@query | http://foo_bar/u:p@path?q=u:p@query          ",
    "Relative path         | /u:p@path                           | /u:p@path                                    ",
    "Opaque URI            | mailto:user:pass@host               | mailto:user:pass@host                        "
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
