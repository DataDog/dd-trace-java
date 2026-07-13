package datadog.trace.instrumentation.servlet3;

import datadog.trace.api.rum.RumInjector;
import datadog.trace.bootstrap.instrumentation.buffer.ByteMatcher;
import datadog.trace.bootstrap.instrumentation.buffer.CharMatcher;
import datadog.trace.bootstrap.instrumentation.buffer.HtmlByteMatcher;
import datadog.trace.bootstrap.instrumentation.buffer.HtmlCharMatcher;
import datadog.trace.bootstrap.instrumentation.buffer.InjectingPipeOutputStream;
import datadog.trace.bootstrap.instrumentation.buffer.InjectingPipeWriter;
import datadog.trace.bootstrap.instrumentation.buffer.LiteralByteMatcher;
import datadog.trace.bootstrap.instrumentation.buffer.LiteralCharMatcher;
import datadog.trace.bootstrap.instrumentation.rum.RumControllableResponse;
import datadog.trace.util.MethodHandles;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.invoke.MethodHandle;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.LongConsumer;
import javax.servlet.ServletContext;
import javax.servlet.ServletOutputStream;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;

public class RumHttpServletResponseWrapper extends HttpServletResponseWrapper
    implements RumControllableResponse {
  /** The {@code </head>} marker encoded in ASCII, used to detect ASCII-compatible charsets. */
  private static final byte[] ASCII_MARKER = "</head>".getBytes(StandardCharsets.US_ASCII);

  private final RumInjector rumInjector;
  private final String servletVersion;
  private WrappedServletOutputStream outputStream;
  private PrintWriter printWriter;
  private InjectingPipeWriter wrappedPipeWriter;
  private boolean shouldInject = true;
  private String contentEncoding = null;

  private static final MethodHandle SET_CONTENT_LENGTH_LONG = getMh("setContentLengthLong");

  private static MethodHandle getMh(final String name) {
    try {
      return new MethodHandles(ServletResponse.class.getClassLoader())
          .method(ServletResponse.class, name);
    } catch (Throwable ignored) {
      return null;
    }
  }

  private static <E extends Throwable> void sneakyThrow(Throwable e) throws E {
    throw (E) e;
  }

  public RumHttpServletResponseWrapper(HttpServletRequest request, HttpServletResponse response) {
    super(response);
    this.rumInjector = RumInjector.get();

    String version = "3";
    ServletContext servletContext = request.getServletContext();
    if (servletContext != null) {
      try {
        version = String.valueOf(servletContext.getEffectiveMajorVersion());
      } catch (Exception e) {
      }
    }
    this.servletVersion = version;
  }

  @Override
  public ServletOutputStream getOutputStream() throws IOException {
    if (outputStream != null) {
      return outputStream;
    }
    if (!shouldInject) {
      RumInjector.getTelemetryCollector().onInjectionSkipped(servletVersion);
      return super.getOutputStream();
    }
    try {
      String encoding = getCharacterEncoding();
      if (encoding == null) {
        encoding = Charset.defaultCharset().name();
      }
      ServletOutputStream delegate = super.getOutputStream();
      byte[] markerBytes = rumInjector.getMarkerBytes(encoding);
      byte[] snippetBytes = rumInjector.getSnippetBytes(encoding);
      LongConsumer onSize =
          bytes ->
              RumInjector.getTelemetryCollector().onInjectionResponseSize(servletVersion, bytes);
      LongConsumer onTime =
          ms -> RumInjector.getTelemetryCollector().onInjectionTime(servletVersion, ms);
      // The HTML parser scans ASCII delimiters directly, so it only works for ASCII-compatible
      // byte encodings; other charsets fall back to literal marker matching.
      ByteMatcher matcher =
          rumInjector.isHtmlParserEnabled() && isAsciiCompatible(markerBytes)
              ? new HtmlByteMatcher()
              : new LiteralByteMatcher(markerBytes);
      InjectingPipeOutputStream pipe =
          new InjectingPipeOutputStream(
              delegate, snippetBytes, matcher, this::onInjected, onSize, onTime);
      outputStream = new WrappedServletOutputStream(delegate, pipe);
    } catch (Exception e) {
      RumInjector.getTelemetryCollector().onInjectionFailed(servletVersion, contentEncoding);
      throw e;
    }

    return outputStream;
  }

  @Override
  public PrintWriter getWriter() throws IOException {
    if (printWriter != null) {
      return printWriter;
    }
    if (!shouldInject) {
      RumInjector.getTelemetryCollector().onInjectionSkipped(servletVersion);
      return super.getWriter();
    }
    try {
      PrintWriter delegate = super.getWriter();
      char[] snippetChars = rumInjector.getSnippetChars();
      LongConsumer onSize =
          bytes ->
              RumInjector.getTelemetryCollector().onInjectionResponseSize(servletVersion, bytes);
      LongConsumer onTime =
          ms -> RumInjector.getTelemetryCollector().onInjectionTime(servletVersion, ms);
      // the char pipe works on decoded chars, so the HTML parser is always safe here.
      CharMatcher matcher =
          rumInjector.isHtmlParserEnabled()
              ? new HtmlCharMatcher()
              : new LiteralCharMatcher(rumInjector.getMarkerChars());
      wrappedPipeWriter =
          new InjectingPipeWriter(
              delegate, snippetChars, matcher, this::onInjected, onSize, onTime);
      printWriter = new PrintWriter(wrappedPipeWriter);
    } catch (Exception e) {
      RumInjector.getTelemetryCollector().onInjectionFailed(servletVersion, contentEncoding);
      throw e;
    }

    return printWriter;
  }

  @Override
  public void setHeader(String name, String value) {
    if (shouldInject) {
      if (isContentLengthHeader(name)) {
        return;
      }
      checkForContentType(name, value);
      checkForContentSecurityPolicy(name);
    }
    super.setHeader(name, value);
  }

  @Override
  public void addHeader(String name, String value) {
    if (shouldInject) {
      if (isContentLengthHeader(name)) {
        return;
      }
      checkForContentType(name, value);
      checkForContentSecurityPolicy(name);
    }
    super.addHeader(name, value);
  }

  private static boolean isAsciiCompatible(byte[] markerBytes) {
    // The charset is ASCII-compatible when "</head>" encodes to the exact same single bytes as
    // ASCII (true for UTF-8, ISO-8859-1, Windows-1252, ...; false for UTF-16/UTF-32).
    return Arrays.equals(markerBytes, ASCII_MARKER);
  }

  private boolean isContentLengthHeader(String name) {
    return "content-length".equalsIgnoreCase(name);
  }

  private void checkForContentSecurityPolicy(String name) {
    if ("content-security-policy".equalsIgnoreCase(name)) {
      RumInjector.getTelemetryCollector().onContentSecurityPolicyDetected(servletVersion);
    }
  }

  @Override
  public void setContentLength(int len) {
    // don't set it since we don't know if we will inject
    if (!shouldInject) {
      super.setContentLength(len);
    }
  }

  @Override
  public void setContentLengthLong(long len) {
    if (!shouldInject && SET_CONTENT_LENGTH_LONG != null) {
      try {
        SET_CONTENT_LENGTH_LONG.invoke(getResponse(), len);
      } catch (Throwable t) {
        sneakyThrow(t);
      }
    }
  }

  @Override
  public void setCharacterEncoding(String charset) {
    if (charset != null) {
      this.contentEncoding = charset;
    }
    super.setCharacterEncoding(charset);
  }

  @Override
  public void reset() {
    this.outputStream = null;
    this.wrappedPipeWriter = null;
    this.printWriter = null;
    this.shouldInject = false;
    super.reset();
  }

  @Override
  public void resetBuffer() {
    this.outputStream = null;
    this.wrappedPipeWriter = null;
    this.printWriter = null;
    super.resetBuffer();
  }

  public void onInjected() {
    RumInjector.getTelemetryCollector().onInjectionSucceed(servletVersion);
    try {
      setHeader("x-datadog-rum-injected", "1");
    } catch (Throwable ignored) {
      // suppress exception if arisen setting this header by us.
    }
  }

  private void handleContentType(String type) {
    final boolean wasInjecting = shouldInject;
    if (shouldInject) {
      shouldInject = type != null && type.contains("text/html");
    }
    if (wasInjecting && !shouldInject) {
      commit();
      stopFiltering();
    }
  }

  private void checkForContentType(String name, String value) {
    if ("content-type".equalsIgnoreCase(name)) {
      handleContentType(value);
    }
  }

  @Override
  public void setContentType(String type) {
    handleContentType(type);
    super.setContentType(type);
  }

  @Override
  public void commit() {
    if (wrappedPipeWriter != null) {
      try {
        wrappedPipeWriter.commit();
      } catch (Throwable ignored) {
      }
    }
    if (outputStream != null) {
      try {
        outputStream.commit();
      } catch (Throwable ignored) {
      }
    }
  }

  @Override
  public void stopFiltering() {
    shouldInject = false;
    if (wrappedPipeWriter != null) {
      wrappedPipeWriter.setFilter(false);
    }
    if (outputStream != null) {
      outputStream.setFilter(false);
    }
  }
}
