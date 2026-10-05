package datadog.trace.instrumentation.apachehttpasyncclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.apache.http.HttpHost;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpRequestWrapper;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
import org.junit.jupiter.api.Test;

class ApacheHttpClientRedirectInstrumentationTest {

  @Test
  void doesNotCopyApplicationHeadersToCrossOriginRedirects() throws Exception {
    HttpGet original = originalRequest("http://example.com/request");
    original.addHeader("Authorization", "Bearer secret");
    original.addHeader("Cookie", "session=secret");
    original.addHeader("X-Api-Key", "secret");
    original.addHeader("x-datadog-trace-id", "123");

    HttpGet redirect = new HttpGet("http://attacker.example/redirect");

    ApacheHttpClientRedirectInstrumentation.ClientRedirectAdvice.onAfterExecute(
        contextWith(original), redirect);

    assertFalse(redirect.containsHeader("Authorization"));
    assertFalse(redirect.containsHeader("Cookie"));
    assertFalse(redirect.containsHeader("X-Api-Key"));
    assertEquals("123", redirect.getFirstHeader("x-datadog-trace-id").getValue());
  }

  @Test
  void preventsApacheHeaderCopyWhenCrossOriginRedirectHasNoPropagationHeaders() throws Exception {
    HttpGet original = originalRequest("http://example.com/request");
    original.addHeader("Authorization", "Bearer secret");
    original.addHeader("Cookie", "session=secret");

    HttpGet redirect = new HttpGet("http://attacker.example/redirect");

    ApacheHttpClientRedirectInstrumentation.ClientRedirectAdvice.onAfterExecute(
        contextWith(original), redirect);

    assertFalse(redirect.containsHeader("Authorization"));
    assertFalse(redirect.containsHeader("Cookie"));
    assertEquals("true", redirect.getFirstHeader("x-datadog-redirect").getValue());
  }

  @Test
  void copiesApplicationHeadersToSameOriginRedirects() throws Exception {
    HttpGet original = originalRequest("https://example.com/request");
    original.addHeader("Authorization", "Bearer secret");
    original.addHeader("x-datadog-trace-id", "123");

    HttpGet redirect = new HttpGet("https://example.com/redirect");

    ApacheHttpClientRedirectInstrumentation.ClientRedirectAdvice.onAfterExecute(
        contextWith(original), redirect);

    assertEquals("Bearer secret", redirect.getFirstHeader("Authorization").getValue());
    assertEquals("123", redirect.getFirstHeader("x-datadog-trace-id").getValue());
  }

  @Test
  void treatsDefaultPortsAsSameOrigin() throws Exception {
    HttpGet original = originalRequest("https://example.com:443/request");
    original.addHeader("Authorization", "Bearer secret");

    HttpGet redirect = new HttpGet("https://example.com/redirect");

    ApacheHttpClientRedirectInstrumentation.ClientRedirectAdvice.onAfterExecute(
        contextWith(original), redirect);

    assertEquals("Bearer secret", redirect.getFirstHeader("Authorization").getValue());
  }

  @Test
  void resolvesHostRequestOriginFromContext() throws Exception {
    HttpGet original = originalRequest("/request");
    original.addHeader("Authorization", "Bearer secret");

    HttpGet redirect = new HttpGet("http://example.com/redirect");
    HttpContext context = contextWith(original);
    context.setAttribute("http.target_host", new HttpHost("example.com", 80, "http"));

    ApacheHttpClientRedirectInstrumentation.ClientRedirectAdvice.onAfterExecute(context, redirect);

    assertEquals("Bearer secret", redirect.getFirstHeader("Authorization").getValue());
  }

  private static HttpGet originalRequest(final String uri) {
    return new HttpGet(uri);
  }

  private static HttpContext contextWith(final HttpGet request) throws Exception {
    HttpContext context = new BasicHttpContext();
    context.setAttribute("http.request", HttpRequestWrapper.wrap(request));
    return context;
  }
}
