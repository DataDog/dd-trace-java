package datadog.trace.instrumentation.servlet3;

import datadog.trace.api.rum.RumInjector;
import datadog.trace.bootstrap.instrumentation.buffer.InjectingPipeWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;

public class RumHttpServletResponseWrapper extends HttpServletResponseWrapper {
  private final RumInjector rumInjector;
  private ServletOutputStream outputStream;
  private PrintWriter printWriter;
  private boolean shouldInject = false;
  private long injectionStartTime = -1;

  public RumHttpServletResponseWrapper(HttpServletResponse response) {
    super(response);
    this.rumInjector = RumInjector.get();
  }

  @Override
  public ServletOutputStream getOutputStream() throws IOException {
    if (!shouldInject) {
      RumInjector.getTelemetryCollector().onInjectionSkipped();
      return super.getOutputStream();
    }
    if (outputStream == null) {
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
                this::onInjected);
      } catch (Exception e) {
        RumInjector.getTelemetryCollector().onInjectionFailed();
        throw e;
      }
    }
    return outputStream;
  }

  @Override
  public PrintWriter getWriter() throws IOException {
    if (!shouldInject) {
      RumInjector.getTelemetryCollector().onInjectionSkipped();
      return super.getWriter();
    }
    if (printWriter == null) {
      // start timing injection
      if (injectionStartTime == -1) {
        injectionStartTime = System.nanoTime();
      }
      try {
        printWriter =
            new PrintWriter(
                new InjectingPipeWriter(
                    super.getWriter(),
                    rumInjector.getMarkerChars(),
                    rumInjector.getSnippetChars(),
                    this::onInjected));
      } catch (Exception e) {
        injectionStartTime = -1;
        RumInjector.getTelemetryCollector().onInjectionFailed();
        throw e;
      }
    }
    return printWriter;
  }

  @Override
  public void setHeader(String name, String value) {
    if (name != null) {
      String lowerName = name.toLowerCase();
      if (lowerName.startsWith("content-security-policy")) {
        RumInjector.getTelemetryCollector().onContentSecurityPolicyDetected();
      } else if (lowerName.equals("content-length") && value != null) {
        try {
          long contentLength = Long.parseLong(value);
          RumInjector.getTelemetryCollector().onInjectionResponseSize(contentLength);
        } catch (NumberFormatException ignored) {
          // ignore?
        }
      }
    }
    super.setHeader(name, value);
  }

  @Override
  public void addHeader(String name, String value) {
    if (name != null) {
      String lowerName = name.toLowerCase();
      if (lowerName.startsWith("content-security-policy")) {
        RumInjector.getTelemetryCollector().onContentSecurityPolicyDetected();
      }
    }
    super.addHeader(name, value);
  }

  @Override
  public void setContentLength(int len) {
    if (len >= 0) {
      RumInjector.getTelemetryCollector().onInjectionResponseSize(len);
    }
    // don't set it since we don't know if we will inject
  }

  @Override
  public void reset() {
    this.outputStream = null;
    this.printWriter = null;
    this.shouldInject = false;
    this.injectionStartTime = -1;
    super.reset();
  }

  @Override
  public void resetBuffer() {
    this.outputStream = null;
    this.printWriter = null;
    this.shouldInject = false;
    this.injectionStartTime = -1;
    super.resetBuffer();
  }

  public void onInjected() {
    RumInjector.getTelemetryCollector().onInjectionSucceed();

    // report injection time
    if (injectionStartTime != -1) {
      long nanoseconds = System.nanoTime() - injectionStartTime;
      long milliseconds = nanoseconds / 1_000_000L;
      RumInjector.getTelemetryCollector().onInjectionTime(milliseconds);
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
    shouldInject = type != null && type.contains("text/html");
    super.setContentType(type);
  }
}
