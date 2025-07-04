package datadog.trace.instrumentation.servlet3;

import datadog.trace.api.rum.RumInjector;
import java.io.IOException;
import java.io.PrintWriter;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;

public class RumHttpServletResponseWrapper extends HttpServletResponseWrapper {
  private ServletOutputStream outputStream;
  private PrintWriter printWriter;
  private boolean shouldInject;

  public RumHttpServletResponseWrapper(HttpServletResponse response) {
    super(response);
  }

  @Override
  public ServletOutputStream getOutputStream() throws IOException {
    if (!shouldInject) {
      return super.getOutputStream();
    }
    if (outputStream == null) {
      String encoding = getCharacterEncoding();
      if (encoding == null) {
        encoding = "UTF-8";
      }
      outputStream =
          new WrappedServletOutputStream(
              super.getOutputStream(),
              RumInjector.getMarker(encoding),
              RumInjector.getSnippet(encoding),
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

  public void onInjected(Void ignored) {
    try {
      setHeader("x-datadog-rum-injected", "1");
    } catch (Throwable ignored2) {
    }
  }

  @Override
  public void setContentType(String type) {
    if (type != null && type.contains("html")) {
      shouldInject = true;
    }
  }
}
