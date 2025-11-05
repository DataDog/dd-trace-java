import datadog.trace.agent.test.InstrumentationSpecification
import groovy.servlet.AbstractHttpServlet

import javax.servlet.ServletException
import javax.servlet.ServletOutputStream
import javax.servlet.ServletRequest
import javax.servlet.ServletResponse
import javax.servlet.http.Cookie
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

import static java.util.Collections.emptyEnumeration

class HttpServletResponseTest extends InstrumentationSpecification {

  def request = Mock(HttpServletRequest) {
    getMethod() >> "GET"
    getProtocol() >> "TEST"
    getHeaderNames() >> emptyEnumeration()
    getAttributeNames() >> emptyEnumeration()
  }

  def doService(HttpServletRequest request, TestResponse response, Closure<HttpServletResponse> testHandler) {
    def servlet = new AbstractHttpServlet() {
        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
          testHandler(resp)
        }
      }
    servlet.service((ServletRequest) request, (ServletResponse) response)
  }

  def "test send no-parent"() {
    when:
    doService(request, new TestResponse(), handler)

    then:
    assertTraces(1) {
      trace(2) {
        span {
          operationName "servlet.request"
          spanType "web"
          tags {
            "component" "java-web-servlet"
            "http.method" "GET"
            "http.url" "/"
            "span.kind" "server"
            "http.status_code" { it == null || it == 302 }
            defaultTags()
          }
        }
        span {
          operationName "servlet.response"
          resourceName "HttpServletResponse." + resourceSuffix
          tags {
            "component" "java-web-servlet-response"
            defaultTags()
          }
        }
      }
    }

    where:
    resourceSuffix    | handler
    "sendError"       | {HttpServletResponse r -> r.sendError(0)}
    "sendError"       | {HttpServletResponse r -> r.sendError(0, "")}
    "sendRedirect"    | {HttpServletResponse r -> r.sendRedirect("")}
  }

  def "test send with exception"() {
    setup:
    def ex = new Exception("some error")

    when:
    doService(request, new TestResponse() {
        @Override
        void sendRedirect(String s) {
          throw ex
        }
      }, handler)

    then:
    def th = thrown(Exception)
    th == ex

    assertTraces(1) {
      trace(2) {
        span {
          operationName "servlet.request"
          spanType "web"
          errored true
          tags {
            "component" "java-web-servlet"
            "http.method" "GET"
            "http.url" "/"
            "span.kind" "server"
            "http.status_code" Integer
            defaultTags()
            errorTags(ex.class, ex.message)
          }
        }
        span {
          operationName "servlet.response"
          resourceName "HttpServletResponse." + resourceSuffix
          errored true
          tags {
            "component" "java-web-servlet-response"
            defaultTags()
            errorTags(ex.class, ex.message)
          }
        }
      }
    }
    where:
    resourceSuffix    | handler
    "sendRedirect"    | {HttpServletResponse r -> r.sendRedirect("")}
  }

  static class TestResponse implements HttpServletResponse {

    @Override
    void addCookie(Cookie cookie) {
    }

    @Override
    boolean containsHeader(String s) {
      return false
    }

    @Override
    String encodeURL(String s) {
      return null
    }

    @Override
    String encodeRedirectURL(String s) {
      return null
    }

    @Override
    String encodeUrl(String s) {
      return null
    }

    @Override
    String encodeRedirectUrl(String s) {
      return null
    }

    @Override
    void sendError(int i, String s) throws IOException {
    }

    @Override
    void sendError(int i) throws IOException {
    }

    @Override
    void sendRedirect(String s) throws IOException {
    }

    @Override
    void setDateHeader(String s, long l) {
    }

    @Override
    void addDateHeader(String s, long l) {
    }

    @Override
    void setHeader(String s, String s1) {
    }

    @Override
    void addHeader(String s, String s1) {
    }

    @Override
    void setIntHeader(String s, int i) {
    }

    @Override
    void addIntHeader(String s, int i) {
    }

    @Override
    void setStatus(int i) {
    }

    @Override
    void setStatus(int i, String s) {
    }

    @Override
    String getCharacterEncoding() {
      return null
    }

    @Override
    ServletOutputStream getOutputStream() throws IOException {
      return null
    }

    @Override
    PrintWriter getWriter() throws IOException {
      return null
    }

    @Override
    void setContentLength(int i) {
    }

    @Override
    void setContentType(String s) {
    }

    @Override
    void setBufferSize(int i) {
    }

    @Override
    int getBufferSize() {
      return 0
    }

    @Override
    void flushBuffer() throws IOException {
    }

    @Override
    void resetBuffer() {
    }

    @Override
    boolean isCommitted() {
      return false
    }

    @Override
    void reset() {
    }

    @Override
    void setLocale(Locale locale) {
    }

    @Override
    Locale getLocale() {
      return null
    }
  }
}
