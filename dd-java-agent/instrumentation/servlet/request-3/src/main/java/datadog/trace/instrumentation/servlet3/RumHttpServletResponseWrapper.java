package datadog.trace.instrumentation.servlet3;

import datadog.trace.api.rum.RumInjector;
import datadog.trace.bootstrap.instrumentation.buffer.InjectingPipeWriter;
import datadog.trace.util.MethodHandles;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.invoke.MethodHandle;
import java.nio.charset.Charset;
import javax.servlet.ServletOutputStream;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;

public class RumHttpServletResponseWrapper extends HttpServletResponseWrapper {
  private final RumInjector rumInjector;
  private WrappedServletOutputStream outputStream;
  private PrintWriter printWriter;
  private InjectingPipeWriter wrappedPipeWriter;
  private boolean shouldInject = true;
  private long injectionStartTime = -1;
  private String contentEncoding = "none";

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

  public RumHttpServletResponseWrapper(HttpServletResponse response) {
    super(response);
    this.rumInjector = RumInjector.get();
  }

  @Override
  public ServletOutputStream getOutputStream() throws IOException {
    if (outputStream != null) {
      return outputStream;
    }
    if (!shouldInject) {
      RumInjector.getTelemetryCollector().onInjectionSkipped("3");
      return super.getOutputStream();
    }
    // start timing injection
    if (injectionStartTime == -1) {
      injectionStartTime = System.nanoTime();
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
              bytes -> RumInjector.getTelemetryCollector().onInjectionResponseSize("3", bytes));
    } catch (Exception e) {
      RumInjector.getTelemetryCollector().onInjectionFailed("3", contentEncoding);
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
      RumInjector.getTelemetryCollector().onInjectionSkipped("3");
      return super.getWriter();
    }
    // start timing injection
    if (injectionStartTime == -1) {
      injectionStartTime = System.nanoTime();
    }
    try {
      wrappedPipeWriter =
          new InjectingPipeWriter(
              super.getWriter(),
              rumInjector.getMarkerChars(),
              rumInjector.getSnippetChars(),
              this::onInjected);
      printWriter = new PrintWriter(wrappedPipeWriter);
    } catch (Exception e) {
      RumInjector.getTelemetryCollector().onInjectionFailed("3", contentEncoding);
      throw e;
    }

    return printWriter;
  }

  @Override
  public void setHeader(String name, String value) {
    if (name != null) {
      String lowerName = name.toLowerCase();
      if (lowerName.startsWith("content-security-policy")) {
        RumInjector.getTelemetryCollector().onContentSecurityPolicyDetected("3");
      } else if (lowerName.equals("content-encoding")) {
        this.contentEncoding = value;
      }
    }
    super.setHeader(name, value);
  }

  @Override
  public void addHeader(String name, String value) {
    if (name != null) {
      String lowerName = name.toLowerCase();
      if (lowerName.startsWith("content-security-policy")) {
        RumInjector.getTelemetryCollector().onContentSecurityPolicyDetected("3");
      } else if (lowerName.equals("content-encoding")) {
        this.contentEncoding = value;
      }
    }
    super.addHeader(name, value);
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
  public void reset() {
    this.outputStream = null;
    this.wrappedPipeWriter = null;
    this.printWriter = null;
    this.shouldInject = false;
    this.injectionStartTime = -1;
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
    RumInjector.getTelemetryCollector().onInjectionSucceed("3");

    // report injection time
    if (injectionStartTime != -1) {
      long nanoseconds = System.nanoTime() - injectionStartTime;
      long milliseconds = nanoseconds / 1_000_000L;
      RumInjector.getTelemetryCollector().onInjectionTime("3", milliseconds);
      injectionStartTime = -1;
    }

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
