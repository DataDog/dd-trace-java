package datadog.trace.instrumentation.servlet5;

import datadog.trace.api.rum.RumInjector;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.Charset;

public class RumHttpServletResponseWrapper extends HttpServletResponseWrapper {
  private final RumInjector rumInjector;
  private ServletOutputStream outputStream;
  private PrintWriter printWriter;
  private boolean shouldInject = false;

  public RumHttpServletResponseWrapper(HttpServletResponse response) {
    super(response);
    this.rumInjector = RumInjector.get();
  }

  @Override
  public ServletOutputStream getOutputStream() throws IOException {
    if (!shouldInject) {
      return super.getOutputStream();
    }
    if (outputStream == null) {
      String encoding = getCharacterEncoding();
      if (encoding == null) {
        encoding = Charset.defaultCharset().name();
      }
      outputStream =
          new WrappedServletOutputStream(
              super.getOutputStream(),
              rumInjector.getMarker(encoding),
              rumInjector.getSnippet(encoding),
              this::onInjected);
    }
    return outputStream;
  }

  @Override
  public PrintWriter getWriter() throws IOException {
    if (!shouldInject) {
      return super.getWriter();
    }
    if (printWriter == null) {
      printWriter = new PrintWriter(getOutputStream());
    }
    return printWriter;
  }

  @Override
  public void setContentLength(int len) {
    // don't set it since we don't know if we will inject
  }

  @Override
  public void reset() {
    this.outputStream = null;
    this.printWriter = null;
    this.shouldInject = false;
    super.reset();
  }

  @Override
  public void resetBuffer() {
    this.outputStream = null;
    this.printWriter = null;
    this.shouldInject = false;
    super.resetBuffer();
  }

  public void onInjected() {
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
