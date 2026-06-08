package datadog.trace.instrumentation.servlet5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.api.iast.sink.HttpResponseHeaderModule;
import datadog.trace.api.iast.sink.UnvalidatedRedirectModule;
import datadog.trace.junit.utils.config.WithConfig;
import foo.bar.smoketest.DummyResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.lang.reflect.Method;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@WithConfig(key = "iast.enabled", value = "true")
class JakartaHttpServletResponseInstrumentationTest extends AbstractInstrumentationTest {

  @AfterEach
  void clearModules() throws Exception {
    // InstrumentationBridge.clearIastModules() is package-private (test-only). Invoke it
    // reflectively to reset the global IAST module state between tests without widening its
    // visibility in internal-api.
    Method clear = InstrumentationBridge.class.getDeclaredMethod("clearIastModules");
    clear.setAccessible(true);
    clear.invoke(null);
  }

  @Test
  void insecureCookieAddedUsingAddCookie() {
    HttpResponseHeaderModule module = mock(HttpResponseHeaderModule.class);
    InstrumentationBridge.registerIastModule(module);
    DummyResponse response = new DummyResponse();
    Cookie cookie = new Cookie("user-id", "7");
    cookie.setMaxAge(3);

    response.addCookie(cookie);

    ArgumentCaptor<datadog.trace.api.iast.util.Cookie> captor =
        ArgumentCaptor.forClass(datadog.trace.api.iast.util.Cookie.class);
    verify(module).onCookie(captor.capture());
    datadog.trace.api.iast.util.Cookie captured = captor.getValue();
    assertEquals(cookie.getName(), captured.getCookieName());
    assertEquals(cookie.getValue(), captured.getCookieValue());
    assertEquals(cookie.getSecure(), captured.isSecure());
    assertEquals(cookie.isHttpOnly(), captured.isHttpOnly());
    assertEquals(cookie.getMaxAge(), captured.getMaxAge());
    verifyNoMoreInteractions(module);
  }

  @Test
  void doNotInstrumentSubclassesOfHttpServletResponseWrapper() {
    HttpResponseHeaderModule module = mock(HttpResponseHeaderModule.class);
    InstrumentationBridge.registerIastModule(module);
    HttpServletResponse request = mock(HttpServletResponse.class);
    HttpServletResponseWrapper wrapper = new HttpServletResponseWrapper(request);
    Cookie cookie = new Cookie("user-id", "7");

    wrapper.addCookie(cookie);

    verify(request).addCookie(cookie);
    verifyNoInteractions(module);
  }

  @Test
  void secureCookieAddedUsingAddCookie() {
    HttpResponseHeaderModule module = mock(HttpResponseHeaderModule.class);
    InstrumentationBridge.registerIastModule(module);
    DummyResponse response = new DummyResponse();
    Cookie cookie = new Cookie("user-id", "7");
    cookie.setSecure(true);
    cookie.setMaxAge(3);

    response.addCookie(cookie);

    ArgumentCaptor<datadog.trace.api.iast.util.Cookie> captor =
        ArgumentCaptor.forClass(datadog.trace.api.iast.util.Cookie.class);
    verify(module).onCookie(captor.capture());
    datadog.trace.api.iast.util.Cookie captured = captor.getValue();
    assertEquals(cookie.getName(), captured.getCookieName());
    assertEquals(cookie.getValue(), captured.getCookieValue());
    assertEquals(cookie.getSecure(), captured.isSecure());
    assertEquals(cookie.isHttpOnly(), captured.isHttpOnly());
    assertEquals(cookie.getMaxAge(), captured.getMaxAge());
    verifyNoMoreInteractions(module);
  }

  @Test
  void nullCookieAddedUsingAddCookie() {
    HttpResponseHeaderModule module = mock(HttpResponseHeaderModule.class);
    InstrumentationBridge.registerIastModule(module);
    DummyResponse response = new DummyResponse();

    response.addCookie((Cookie) null);

    verifyNoInteractions(module);
  }

  @Test
  void insecureCookieAddedUsingAddHeader() {
    HttpResponseHeaderModule module = mock(HttpResponseHeaderModule.class);
    InstrumentationBridge.registerIastModule(module);
    DummyResponse response = new DummyResponse();

    response.addHeader("Set-Cookie", "user-id=7");

    verify(module).onHeader("Set-Cookie", "user-id=7");
    verifyNoMoreInteractions(module);
  }

  @Test
  void nullParametersAddedUsingAddHeader() {
    HttpResponseHeaderModule module = mock(HttpResponseHeaderModule.class);
    InstrumentationBridge.registerIastModule(module);
    DummyResponse response = new DummyResponse();

    response.addHeader((String) null, null);

    verifyNoInteractions(module);
  }

  @Test
  void insecureCookieAddedUsingSetHeader() {
    HttpResponseHeaderModule module = mock(HttpResponseHeaderModule.class);
    InstrumentationBridge.registerIastModule(module);
    DummyResponse response = new DummyResponse();

    response.setHeader("Set-Cookie", "user-id=7");

    verify(module).onHeader("Set-Cookie", "user-id=7");
    verifyNoMoreInteractions(module);
  }

  @Test
  void nullParametersAddedUsingSetHeader() {
    HttpResponseHeaderModule module = mock(HttpResponseHeaderModule.class);
    InstrumentationBridge.registerIastModule(module);
    DummyResponse response = new DummyResponse();

    response.setHeader((String) null, null);

    verifyNoInteractions(module);
  }

  @Test
  void unvalidatedRedirectCheckedUsingAddHeader() {
    HttpResponseHeaderModule module = mock(HttpResponseHeaderModule.class);
    InstrumentationBridge.registerIastModule(module);
    DummyResponse response = new DummyResponse();

    response.addHeader("Location", "http://dummy.url.com");

    verify(module).onHeader("Location", "http://dummy.url.com");
    verifyNoMoreInteractions(module);
  }

  @Test
  void unvalidatedRedirectCheckedSetHeader() {
    HttpResponseHeaderModule module = mock(HttpResponseHeaderModule.class);
    InstrumentationBridge.registerIastModule(module);
    DummyResponse response = new DummyResponse();

    response.setHeader("Location", "http://dummy.url.com");

    verify(module).onHeader("Location", "http://dummy.url.com");
    verifyNoMoreInteractions(module);
  }

  @Test
  void redirectionAddedUsingSendRedirect() throws Exception {
    UnvalidatedRedirectModule module = mock(UnvalidatedRedirectModule.class);
    InstrumentationBridge.registerIastModule(module);
    DummyResponse response = new DummyResponse();

    response.sendRedirect("http://dummy.location.com");

    // The 1-arg form delegates to the 3-arg overload (Servlet 6.1); the call-depth guard ensures
    // a single logical redirect is detected exactly once.
    verify(module, times(1)).onRedirect("http://dummy.location.com");
    verifyNoMoreInteractions(module);
  }

  @Test
  void nullLocationAddedUsingSendRedirect() throws Exception {
    UnvalidatedRedirectModule module = mock(UnvalidatedRedirectModule.class);
    InstrumentationBridge.registerIastModule(module);
    DummyResponse response = new DummyResponse();

    response.sendRedirect(null);

    verify(module, never()).onRedirect(any());
    verifyNoMoreInteractions(module);
  }

  @Test
  void redirectionAddedUsingThreeArgSendRedirect() throws Exception {
    UnvalidatedRedirectModule module = mock(UnvalidatedRedirectModule.class);
    InstrumentationBridge.registerIastModule(module);
    DummyResponse response = new DummyResponse();

    // Servlet 6.1 overload: sendRedirect(String location, int sc, boolean clearBuffer).
    response.sendRedirect("http://dummy.location.com", 302, true);

    verify(module, times(1)).onRedirect("http://dummy.location.com");
    verifyNoMoreInteractions(module);
  }

  @Test
  void nullLocationThreeArgSendRedirect() throws Exception {
    UnvalidatedRedirectModule module = mock(UnvalidatedRedirectModule.class);
    InstrumentationBridge.registerIastModule(module);
    DummyResponse response = new DummyResponse();

    response.sendRedirect(null, 302, true);

    verify(module, never()).onRedirect(any());
    verifyNoMoreInteractions(module);
  }

  @Test
  void emptyLocationThreeArgSendRedirect() throws Exception {
    UnvalidatedRedirectModule module = mock(UnvalidatedRedirectModule.class);
    InstrumentationBridge.registerIastModule(module);
    DummyResponse response = new DummyResponse();

    response.sendRedirect("", 302, true);

    verify(module, never()).onRedirect(any());
    verifyNoMoreInteractions(module);
  }

  @Test
  void taintEncodedUrlUsingEncodeRedirectURL() {
    PropagationModule module = mock(PropagationModule.class);
    InstrumentationBridge.registerIastModule(module);
    DummyResponse response = new DummyResponse();
    String url = "http://dummy.url.com";

    String result = response.encodeRedirectURL(url);

    verify(module).taintStringIfTainted(result, url);
    verifyNoMoreInteractions(module);
  }

  @Test
  void taintEncodedUrlUsingEncodeURL() {
    PropagationModule module = mock(PropagationModule.class);
    InstrumentationBridge.registerIastModule(module);
    DummyResponse response = new DummyResponse();
    String url = "http://dummy.url.com";

    String result = response.encodeURL(url);

    verify(module).taintStringIfTainted(result, url);
    verifyNoMoreInteractions(module);
  }

  @Test
  void testInstrumentationWithUnknownTypes() {
    HttpResponseHeaderModule module = mock(HttpResponseHeaderModule.class);
    InstrumentationBridge.registerIastModule(module);
    DummyResponse response = new DummyResponse();

    response.addCookie(new DummyResponse.CustomCookie());
    response.addHeader(new DummyResponse.CustomHeaderName(), "value");
    response.setHeader(new DummyResponse.CustomHeaderName(), "value");

    verifyNoInteractions(module);
  }
}
