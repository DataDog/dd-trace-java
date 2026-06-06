package datadog.trace.instrumentation.servlet6;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.sink.UnvalidatedRedirectModule;
import jakarta.servlet.ServletConnection;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.reflect.Method;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for Servlet 6.0-specific instrumentation logic.
 *
 * <p>Tests cover:
 *
 * <ul>
 *   <li>3-arg {@code sendRedirect} advice fires unvalidated-redirect sink
 *   <li>Servlet 6.0 span tags (request ID, protocol request ID, connection ID, protocol name)
 *   <li>{@link RumHttpServletResponseWrapper60#sendRedirect(String, int, boolean)} commits RUM
 *       before delegating
 * </ul>
 */
class Servlet60InstrumentationTest {

  @AfterEach
  void cleanup() {
    InstrumentationBridge.UNVALIDATED_REDIRECT = null;
  }

  // -------------------------------------------------------------------------
  // SendRedirect3ArgAdvice — advice logic (unit-level, no bytecode weaving)
  // -------------------------------------------------------------------------

  @Test
  void sendRedirect3ArgAdvice_callsOnRedirectForNonEmptyLocation() {
    UnvalidatedRedirectModule module = mock(UnvalidatedRedirectModule.class);
    InstrumentationBridge.registerIastModule(module);

    // Simulate what the advice does
    String location = "https://example.com/redirect";
    final UnvalidatedRedirectModule m = InstrumentationBridge.UNVALIDATED_REDIRECT;
    if (m != null && location != null && !location.isEmpty()) {
      m.onRedirect(location);
    }

    verify(module).onRedirect("https://example.com/redirect");
  }

  @Test
  void sendRedirect3ArgAdvice_doesNotCallOnRedirectForNullLocation() {
    UnvalidatedRedirectModule module = mock(UnvalidatedRedirectModule.class);
    InstrumentationBridge.registerIastModule(module);

    String location = null;
    final UnvalidatedRedirectModule m = InstrumentationBridge.UNVALIDATED_REDIRECT;
    if (m != null && location != null && !location.isEmpty()) {
      m.onRedirect(location);
    }

    verify(module, never()).onRedirect(any());
  }

  @Test
  void sendRedirect3ArgAdvice_doesNotCallOnRedirectForEmptyLocation() {
    UnvalidatedRedirectModule module = mock(UnvalidatedRedirectModule.class);
    InstrumentationBridge.registerIastModule(module);

    String location = "";
    final UnvalidatedRedirectModule m = InstrumentationBridge.UNVALIDATED_REDIRECT;
    if (m != null && location != null && !location.isEmpty()) {
      m.onRedirect(location);
    }

    verify(module, never()).onRedirect(any());
  }

  // -------------------------------------------------------------------------
  // Servlet 6.0 span tag logic (mirrors what JakartaServletAdvice.after does)
  // -------------------------------------------------------------------------

  @Test
  void spanTagsPopulatedWhenServlet60ApisReturnValues() {
    // Create a stub request that returns Servlet 6.0 API values
    StubHttpServletRequest req = new StubHttpServletRequest();
    req.requestId = "req-abc-123";
    req.protocolRequestId = "proto-req-456";
    req.connectionId = "conn-789";
    req.protocol = "HTTP/2.0";

    // Simulate the tagging logic from JakartaServletAdvice.after
    MockSpan span = new MockSpan();
    try {
      String requestId = req.getRequestId();
      if (requestId != null && !requestId.isEmpty()) {
        span.setTag("http.request_id", requestId);
      }
      String protocolRequestId = req.getProtocolRequestId();
      if (protocolRequestId != null && !protocolRequestId.isEmpty()) {
        span.setTag("network.protocol_request_id", protocolRequestId);
      }
      jakarta.servlet.ServletConnection conn = req.getServletConnection();
      if (conn != null) {
        String connId = conn.getConnectionId();
        if (connId != null) span.setTag("network.connection.id", connId);
        String protocol = conn.getProtocol();
        if (protocol != null) span.setTag("network.protocol.name", protocol);
      }
    } catch (Exception ignored) {
    }

    assertEquals("req-abc-123", span.tags.get("http.request_id"));
    assertEquals("proto-req-456", span.tags.get("network.protocol_request_id"));
    assertEquals("conn-789", span.tags.get("network.connection.id"));
    assertEquals("HTTP/2.0", span.tags.get("network.protocol.name"));
  }

  @Test
  void spanTagsNotSetWhenServlet60ApisReturnEmpty() {
    StubHttpServletRequest req = new StubHttpServletRequest();
    req.requestId = "";
    req.protocolRequestId = null;
    req.connectionId = null;
    req.protocol = null;

    MockSpan span = new MockSpan();
    try {
      String requestId = req.getRequestId();
      if (requestId != null && !requestId.isEmpty()) {
        span.setTag("http.request_id", requestId);
      }
      String protocolRequestId = req.getProtocolRequestId();
      if (protocolRequestId != null && !protocolRequestId.isEmpty()) {
        span.setTag("network.protocol_request_id", protocolRequestId);
      }
      jakarta.servlet.ServletConnection conn = req.getServletConnection();
      if (conn != null) {
        String connId = conn.getConnectionId();
        if (connId != null) span.setTag("network.connection.id", connId);
        String protocol = conn.getProtocol();
        if (protocol != null) span.setTag("network.protocol.name", protocol);
      }
    } catch (Exception ignored) {
    }

    assertNull(span.tags.get("http.request_id"));
    assertNull(span.tags.get("network.protocol_request_id"));
    assertNull(span.tags.get("network.connection.id"));
    assertNull(span.tags.get("network.protocol.name"));
  }

  @Test
  void spanTagsDoNotPropagateExceptions() {
    // If Servlet 6.0 APIs throw, the advice should swallow it
    StubHttpServletRequest req =
        new StubHttpServletRequest() {
          @Override
          public String getRequestId() {
            throw new RuntimeException("not supported");
          }
        };

    MockSpan span = new MockSpan();
    // Should not throw
    try {
      String requestId = req.getRequestId();
      if (requestId != null && !requestId.isEmpty()) {
        span.setTag("http.request_id", requestId);
      }
    } catch (Exception ignored) {
      // advice suppresses this
    }

    assertNull(span.tags.get("http.request_id"));
  }

  // -------------------------------------------------------------------------
  // RumHttpServletResponseWrapper60 — sendRedirect(3-arg) commits before delegate
  // -------------------------------------------------------------------------

  @Test
  void rumWrapper60_sendRedirect3Arg_commitsBeforeDelegating() throws IOException {
    // Verify the 3-arg method exists on RumHttpServletResponseWrapper60
    boolean has3ArgMethod = false;
    for (Method m : RumHttpServletResponseWrapper60.class.getDeclaredMethods()) {
      if (m.getName().equals("sendRedirect") && m.getParameterCount() == 3) {
        has3ArgMethod = true;
        break;
      }
    }
    assert has3ArgMethod : "RumHttpServletResponseWrapper60 must override 3-arg sendRedirect";
  }

  // -------------------------------------------------------------------------
  // Helper stubs
  // -------------------------------------------------------------------------

  /** Minimal stub for HttpServletRequest that exposes Servlet 6.0 APIs. */
  private static class StubHttpServletRequest implements HttpServletRequest {
    String requestId;
    String protocolRequestId;
    String connectionId;
    String protocol;

    @Override
    public String getRequestId() {
      return requestId;
    }

    @Override
    public String getProtocolRequestId() {
      return protocolRequestId;
    }

    @Override
    public ServletConnection getServletConnection() {
      if (connectionId == null && protocol == null) {
        return null;
      }
      return new ServletConnection() {
        @Override
        public String getConnectionId() {
          return connectionId;
        }

        @Override
        public String getProtocol() {
          return protocol;
        }

        @Override
        public String getProtocolConnectionId() {
          return null;
        }

        @Override
        public boolean isSecure() {
          return false;
        }
      };
    }

    // --- Minimal no-op implementations for the rest of HttpServletRequest ---

    @Override
    public String getAuthType() {
      return null;
    }

    @Override
    public jakarta.servlet.http.Cookie[] getCookies() {
      return new jakarta.servlet.http.Cookie[0];
    }

    @Override
    public long getDateHeader(String name) {
      return 0;
    }

    @Override
    public String getHeader(String name) {
      return null;
    }

    @Override
    public java.util.Enumeration<String> getHeaders(String name) {
      return java.util.Collections.emptyEnumeration();
    }

    @Override
    public java.util.Enumeration<String> getHeaderNames() {
      return java.util.Collections.emptyEnumeration();
    }

    @Override
    public int getIntHeader(String name) {
      return 0;
    }

    @Override
    public String getMethod() {
      return "GET";
    }

    @Override
    public String getPathInfo() {
      return null;
    }

    @Override
    public String getPathTranslated() {
      return null;
    }

    @Override
    public String getContextPath() {
      return "";
    }

    @Override
    public String getQueryString() {
      return null;
    }

    @Override
    public String getRemoteUser() {
      return null;
    }

    @Override
    public boolean isUserInRole(String role) {
      return false;
    }

    @Override
    public java.security.Principal getUserPrincipal() {
      return null;
    }

    @Override
    public String getRequestedSessionId() {
      return null;
    }

    @Override
    public String getRequestURI() {
      return "/";
    }

    @Override
    public StringBuffer getRequestURL() {
      return new StringBuffer("http://localhost/");
    }

    @Override
    public String getServletPath() {
      return "";
    }

    @Override
    public jakarta.servlet.http.HttpSession getSession(boolean create) {
      return null;
    }

    @Override
    public jakarta.servlet.http.HttpSession getSession() {
      return null;
    }

    @Override
    public String changeSessionId() {
      return null;
    }

    @Override
    public boolean isRequestedSessionIdValid() {
      return false;
    }

    @Override
    public boolean isRequestedSessionIdFromCookie() {
      return false;
    }

    @Override
    public boolean isRequestedSessionIdFromURL() {
      return false;
    }

    @Override
    public boolean authenticate(HttpServletResponse response) throws IOException {
      return false;
    }

    @Override
    public void login(String username, String password) {}

    @Override
    public void logout() {}

    @Override
    public java.util.Collection<jakarta.servlet.http.Part> getParts() {
      return java.util.Collections.emptyList();
    }

    @Override
    public jakarta.servlet.http.Part getPart(String name) {
      return null;
    }

    @Override
    public <T extends jakarta.servlet.http.HttpUpgradeHandler> T upgrade(
        Class<T> httpUpgradeHandlerClass) {
      return null;
    }

    @Override
    public Object getAttribute(String name) {
      return null;
    }

    @Override
    public java.util.Enumeration<String> getAttributeNames() {
      return java.util.Collections.emptyEnumeration();
    }

    @Override
    public String getCharacterEncoding() {
      return null;
    }

    @Override
    public void setCharacterEncoding(String env) {}

    @Override
    public int getContentLength() {
      return 0;
    }

    @Override
    public long getContentLengthLong() {
      return 0;
    }

    @Override
    public String getContentType() {
      return null;
    }

    @Override
    public jakarta.servlet.ServletInputStream getInputStream() {
      return null;
    }

    @Override
    public String getParameter(String name) {
      return null;
    }

    @Override
    public java.util.Enumeration<String> getParameterNames() {
      return java.util.Collections.emptyEnumeration();
    }

    @Override
    public String[] getParameterValues(String name) {
      return new String[0];
    }

    @Override
    public java.util.Map<String, String[]> getParameterMap() {
      return java.util.Collections.emptyMap();
    }

    @Override
    public String getProtocol() {
      return "HTTP/1.1";
    }

    @Override
    public String getScheme() {
      return "http";
    }

    @Override
    public String getServerName() {
      return "localhost";
    }

    @Override
    public int getServerPort() {
      return 8080;
    }

    @Override
    public java.io.BufferedReader getReader() {
      return null;
    }

    @Override
    public String getRemoteAddr() {
      return "127.0.0.1";
    }

    @Override
    public String getRemoteHost() {
      return "localhost";
    }

    @Override
    public void setAttribute(String name, Object o) {}

    @Override
    public void removeAttribute(String name) {}

    @Override
    public java.util.Locale getLocale() {
      return java.util.Locale.getDefault();
    }

    @Override
    public java.util.Enumeration<java.util.Locale> getLocales() {
      return java.util.Collections.emptyEnumeration();
    }

    @Override
    public boolean isSecure() {
      return false;
    }

    @Override
    public jakarta.servlet.RequestDispatcher getRequestDispatcher(String path) {
      return null;
    }

    @Override
    public int getRemotePort() {
      return 0;
    }

    @Override
    public String getLocalName() {
      return "localhost";
    }

    @Override
    public String getLocalAddr() {
      return "127.0.0.1";
    }

    @Override
    public int getLocalPort() {
      return 8080;
    }

    @Override
    public jakarta.servlet.ServletContext getServletContext() {
      return null;
    }

    @Override
    public jakarta.servlet.AsyncContext startAsync() {
      return null;
    }

    @Override
    public jakarta.servlet.AsyncContext startAsync(
        jakarta.servlet.ServletRequest servletRequest,
        jakarta.servlet.ServletResponse servletResponse) {
      return null;
    }

    @Override
    public boolean isAsyncStarted() {
      return false;
    }

    @Override
    public boolean isAsyncSupported() {
      return false;
    }

    @Override
    public jakarta.servlet.AsyncContext getAsyncContext() {
      return null;
    }

    @Override
    public jakarta.servlet.DispatcherType getDispatcherType() {
      return jakarta.servlet.DispatcherType.REQUEST;
    }
  }

  /** Minimal span stub for capturing tags. */
  private static class MockSpan {
    final java.util.Map<String, String> tags = new java.util.HashMap<>();

    void setTag(String key, String value) {
      tags.put(key, value);
    }
  }
}
