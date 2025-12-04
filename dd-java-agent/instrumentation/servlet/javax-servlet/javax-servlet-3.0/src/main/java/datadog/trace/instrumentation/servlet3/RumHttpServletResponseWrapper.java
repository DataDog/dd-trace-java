package datadog.trace.instrumentation.servlet3;

import datadog.trace.api.rum.RumInjector;
import datadog.trace.bootstrap.instrumentation.buffer.InjectingPipeWriter;
import datadog.trace.bootstrap.instrumentation.rum.RumControllableResponse;
import datadog.trace.util.MethodHandles;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.invoke.MethodHandle;
import java.nio.charset.Charset;
import javax.servlet.ServletContext;
import javax.servlet.ServletOutputStream;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;

public class RumHttpServletResponseWrapper extends HttpServletResponseWrapper
    implements RumControllableResponse {
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
      outputStream =
          new WrappedServletOutputStream(
              super.getOutputStream(),
              rumInjector.getMarkerBytes(encoding),
              rumInjector.getSnippetBytes(encoding),
              this::onInjected,
              bytes ->
                  RumInjector.getTelemetryCollector()
                      .onInjectionResponseSize(servletVersion, bytes),
              milliseconds ->
                  RumInjector.getTelemetryCollector()
                      .onInjectionTime(servletVersion, milliseconds));
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
      wrappedPipeWriter =
          new InjectingPipeWriter(
              super.getWriter(),
              rumInjector.getMarkerChars(),
              rumInjector.getSnippetChars(),
              this::onInjected,
              bytes ->
                  RumInjector.getTelemetryCollector()
                      .onInjectionResponseSize(servletVersion, bytes),
              milliseconds ->
                  RumInjector.getTelemetryCollector()
                      .onInjectionTime(servletVersion, milliseconds));
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
      checkForContentSecurityPolicy(name);
    }
    super.addHeader(name, value);
  }

  private boolean isContentLengthHeader(String name) {
    return "content-length".equalsIgnoreCase(name);
  }

  private void checkForContentSecurityPolicy(String name) {
    if (name != null && name.equalsIgnoreCase("content-security-policy")) {
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

  @Override
  public void setContentType(String type) {
    if (shouldInject) {
      shouldInject = type != null && type.contains("text/html");
    }
    if (!shouldInject) {
      commit();
      stopFiltering();
    }
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
