package datadog.trace.instrumentation.jetty12;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/** Raw HTTP/1.1 client helpers and blocking page fixtures shared by the ee10 forked tests. */
final class JettyRawHttp {
  private static final int TEMPLATE_SIZE = 400 * 1024;

  private JettyRawHttp() {}

  /** Returns a 400 KiB HTML blocking page, large enough to outgrow the socket buffers. */
  static byte[] largeHtmlTemplate() {
    String head = "<!DOCTYPE html><html><head><title>Blocked</title></head><body>\n";
    String tail = "</body></html>\n";
    StringBuilder sb = new StringBuilder(TEMPLATE_SIZE).append(head);
    int line = 0;
    while (sb.length() + tail.length() < TEMPLATE_SIZE) {
      sb.append(String.format(Locale.ROOT, "<p>blocked line %08d</p>\n", line++));
    }
    sb.setLength(TEMPLATE_SIZE - tail.length());
    return sb.append(tail).toString().getBytes(UTF_8);
  }

  /** Reads the status line and headers up to and including the empty line. */
  static String readHeaders(InputStream in) throws IOException {
    ByteArrayOutputStream headers = new ByteArrayOutputStream();
    int matched = 0;
    while (matched < 4) {
      int b = in.read();
      if (b < 0) {
        fail("connection closed while reading headers: " + headers.toString("ISO-8859-1"));
      }
      headers.write(b);
      matched = (b == "\r\n\r\n".charAt(matched)) ? matched + 1 : (b == '\r' ? 1 : 0);
    }
    return new String(headers.toByteArray(), ISO_8859_1);
  }

  /** Returns the Content-Length header value, failing when it is missing. */
  static int contentLength(String headers) {
    for (String line : headers.split("\r\n")) {
      int colon = line.indexOf(':');
      if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
        return Integer.parseInt(line.substring(colon + 1).trim());
      }
    }
    return fail("no Content-Length in response headers: " + headers);
  }

  /** Reads exactly {@code length} bytes, failing with the received count on EOF or reset. */
  static byte[] readBody(InputStream in, int length) {
    byte[] body = new byte[length];
    int received = 0;
    try {
      while (received < length) {
        int n = in.read(body, received, length - received);
        if (n < 0) {
          fail(truncated(received, length, "EOF"));
        }
        received += n;
      }
    } catch (IOException e) {
      fail(truncated(received, length, e.toString()), e);
    }
    return body;
  }

  private static String truncated(int received, int length, String reason) {
    return "response body truncated: received "
        + received
        + " of "
        + length
        + " body bytes before "
        + reason;
  }
}
