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
              rumInjector.getMarkerBytes(encoding),
              rumInjector.getSnippetBytes(encoding),
              this::onInjected);
    }
    return outputStream;
  }

  @Override
  public PrintWriter getWriter() throws IOException {
    final PrintWriter delegate = super.getWriter();
    if (!shouldInject) {
      return delegate;
    }
    if (printWriter == null) {
      printWriter =
          new PrintWriter(
              new InjectingPipeWriter(
                  delegate,
                  rumInjector.getMarkerChars(),
                  rumInjector.getSnippetChars(),
                  this::onInjected));
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
