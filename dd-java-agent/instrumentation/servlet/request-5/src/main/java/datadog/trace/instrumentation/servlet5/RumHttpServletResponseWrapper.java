package datadog.trace.instrumentation.servlet5;

import datadog.trace.api.rum.RumInjector;
import datadog.trace.bootstrap.instrumentation.buffer.InjectingPipeWriter;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.Charset;

public class RumHttpServletResponseWrapper extends HttpServletResponseWrapper {
  private final RumInjector rumInjector;
  private WrappedServletOutputStream outputStream;
  private InjectingPipeWriter wrappedPipeWriter;
  private PrintWriter printWriter;
  private boolean shouldInject = true;

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
      return super.getOutputStream();
    }
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
    return outputStream;
  }

  @Override
  public PrintWriter getWriter() throws IOException {
    if (printWriter != null) {
      return printWriter;
    }
    if (!shouldInject) {
      return super.getWriter();
    }
    wrappedPipeWriter =
        new InjectingPipeWriter(
            super.getWriter(),
            rumInjector.getMarkerChars(),
            rumInjector.getSnippetChars(),
            this::onInjected);
    printWriter = new PrintWriter(wrappedPipeWriter);

    return printWriter;
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
    if (!shouldInject) {
      super.setContentLengthLong(len);
    }
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
}
