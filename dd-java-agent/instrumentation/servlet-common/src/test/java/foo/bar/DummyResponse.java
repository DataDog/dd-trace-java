package foo.bar;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Locale;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;

public class DummyResponse implements HttpServletResponse {
  @Override
  public void addCookie(Cookie cookie) {}

  public void addCookie(CustomCookie cookie) {}

  @Override
  public boolean containsHeader(String name) {
    return false;
  }

  @Override
  public String encodeURL(String url) {
    return "Encoded_" + url;
  }

  @Override
  public String encodeRedirectURL(String url) {
    return "Encoded_" + url;
  }

  @Override
  public String encodeUrl(String url) {
    return null;
  }

  @Override
  public String encodeRedirectUrl(String url) {
    return null;
  }

  @Override
  public void sendError(int sc, String msg) throws IOException {}

  @Override
  public void sendError(int sc) throws IOException {}

  @Override
  public void sendRedirect(String location) throws IOException {}

  @Override
  public void setDateHeader(String name, long date) {}

  @Override
  public void addDateHeader(String name, long date) {}

  @Override
  public void setHeader(String name, String value) {}

  public void setHeader(CustomHeaderName name, String value) {}

  @Override
  public void addHeader(String name, String value) {}

  public void addHeader(CustomHeaderName name, String value) {}

  @Override
  public void setIntHeader(String name, int value) {}

  @Override
  public void addIntHeader(String name, int value) {}

  @Override
  public void setStatus(int sc) {}

  @Override
  public void setStatus(int sc, String sm) {}

  @Override
  public String getCharacterEncoding() {
    return null;
  }

  @Override
  public ServletOutputStream getOutputStream() throws IOException {
    return null;
  }

  @Override
  public PrintWriter getWriter() throws IOException {
    return null;
  }

  @Override
  public void setContentLength(int len) {}

  @Override
  public void setContentType(String type) {}

  @Override
  public void setBufferSize(int size) {}

  @Override
  public int getBufferSize() {
    return 0;
  }

  @Override
  public void flushBuffer() throws IOException {}

  @Override
  public void resetBuffer() {}

  @Override
  public boolean isCommitted() {
    return false;
  }

  @Override
  public void reset() {}

  @Override
  public void setLocale(Locale loc) {}

  @Override
  public Locale getLocale() {
    return null;
  }

  public static class CustomCookie {}

  public static class CustomHeaderName {}
}
