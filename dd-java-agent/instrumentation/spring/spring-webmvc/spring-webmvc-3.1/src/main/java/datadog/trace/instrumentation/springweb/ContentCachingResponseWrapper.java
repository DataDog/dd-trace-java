package datadog.trace.instrumentation.springweb;

import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import java.io.*;

public class ContentCachingResponseWrapper extends HttpServletResponseWrapper {
  private ByteArrayOutputStream content = new ByteArrayOutputStream();
  private ServletOutputStream outputStream;
  private PrintWriter writer;
  private Integer contentLength;

  public ContentCachingResponseWrapper(HttpServletResponse response) {
    super(response);
  }

  public void sendError(int sc) throws IOException {
    this.copyBodyToResponse(false);

    try {
      super.sendError(sc);
    } catch (IllegalStateException var3) {
      super.setStatus(sc);
    }
  }

  public void sendError(int sc, String msg) throws IOException {
    this.copyBodyToResponse(false);

    try {
      super.sendError(sc, msg);
    } catch (IllegalStateException var4) {
      super.setStatus(sc, msg);
    }
  }

  public void sendRedirect(String location) throws IOException {
    this.copyBodyToResponse(false);
    super.sendRedirect(location);
  }

  public ServletOutputStream getOutputStream() throws IOException {
    if (this.outputStream == null) {
      this.outputStream =
          new ContentCachingResponseWrapper.ResponseServletOutputStream(
              this.getResponse().getOutputStream());
    }

    return this.outputStream;
  }

  public PrintWriter getWriter() throws IOException {
    if (this.writer == null) {
      String characterEncoding = this.getCharacterEncoding();
      this.writer =
          characterEncoding != null
              ? new ContentCachingResponseWrapper.ResponsePrintWriter(characterEncoding)
              : new ContentCachingResponseWrapper.ResponsePrintWriter("ISO-8859-1");
    }

    return this.writer;
  }

  public void flushBuffer() throws IOException {}

  //  public void setContentLength(int len) {
  //    if (len > this.content.size()) {
  //      this.content.resize(len);
  //    }
  //
  //    this.contentLength = len;
  //  }
  //
  //  public void setContentLengthLong(long len) {
  //    if (len > 2147483647L) {
  //      throw new IllegalArgumentException("Content-Length exceeds ContentCachingResponseWrapper's
  // maximum (2147483647): " + len);
  //    } else {
  //      int lenInt = (int)len;
  //      if (lenInt > this.content.size()) {
  //        this.content.resize(lenInt);
  //      }
  //
  //      this.contentLength = lenInt;
  //    }
  //  }
  //
  //  public void setBufferSize(int size) {
  //    if (size > this.content.size()) {
  //      this.content.resize(size);
  //    }
  //
  //  }

  public void resetBuffer() {
    this.content.reset();
  }

  public void reset() {
    super.reset();
    this.content.reset();
  }

  /**
   * @deprecated
   */
  @Deprecated
  public int getStatusCode() {
    return this.getStatus();
  }

  public byte[] getContentAsByteArray() {
    return this.content.toByteArray();
  }

  public int getContentSize() {
    return this.content.size();
  }

  public void copyBodyToResponse() throws IOException {
    this.copyBodyToResponse(true);
  }

  protected void copyBodyToResponse(boolean complete) throws IOException {
    if (this.content.size() > 0) {
      HttpServletResponse rawResponse = (HttpServletResponse) this.getResponse();
      if ((complete || this.contentLength != null) && !rawResponse.isCommitted()) {
        if (rawResponse.getHeader("Transfer-Encoding") == null) {
          rawResponse.setContentLength(complete ? this.content.size() : this.contentLength);
        }

        this.contentLength = null;
      }

      this.content.writeTo(rawResponse.getOutputStream());
      this.content.reset();
      if (complete) {
        super.flushBuffer();
      }
    }
  }

  private class ResponsePrintWriter extends PrintWriter {
    public ResponsePrintWriter(String characterEncoding) throws UnsupportedEncodingException {
      super(new OutputStreamWriter(ContentCachingResponseWrapper.this.content, characterEncoding));
    }

    public void write(char[] buf, int off, int len) {
      super.write(buf, off, len);
      super.flush();
    }

    public void write(String s, int off, int len) {
      super.write(s, off, len);
      super.flush();
    }

    public void write(int c) {
      super.write(c);
      super.flush();
    }
  }

  class ResponseServletOutputStream extends ServletOutputStream {
    private final ServletOutputStream os;

    public ResponseServletOutputStream(ServletOutputStream os) {
      this.os = os;
    }

    public void write(int b) throws IOException {
      ContentCachingResponseWrapper.this.content.write(b);
    }

    public void write(byte[] b, int off, int len) throws IOException {
      ContentCachingResponseWrapper.this.content.write(b, off, len);
    }

    public boolean isReady() {
      return this.os.isReady();
    }

    public void setWriteListener(WriteListener writeListener) {
      this.os.setWriteListener(writeListener);
    }
  }
}
