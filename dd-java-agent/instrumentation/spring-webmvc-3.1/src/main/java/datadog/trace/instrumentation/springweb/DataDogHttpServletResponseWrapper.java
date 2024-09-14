package datadog.trace.instrumentation.springweb;

import java.io.ByteArrayOutputStream;
import java.io.CharArrayWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;

public class DataDogHttpServletResponseWrapper extends HttpServletResponseWrapper {
  private CharArrayWriter charArrayWriter;
  private Boolean useOutput;
  private Boolean useWrite;
  private Boolean isJson;
  private PrintWriter writer;

  private ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
  private PrintStream printStream = new PrintStream(byteArrayOutputStream);
  private ServletOutputStream servletOutputStream = new CustomServletOutputStream(printStream);

  public DataDogHttpServletResponseWrapper(HttpServletResponse httpServletResponse) {
    super(httpServletResponse);
    charArrayWriter = new CharArrayWriter();
    writer = new PrintWriter(charArrayWriter);
    useWrite = false;
    useOutput = false;
    isJson = false;
  }

  @Override
  public PrintWriter getWriter() throws IOException {
    //System.out.println("get write");
    if (getIsJson()) {
      useWrite = true;
      return writer;
    }
    return super.getWriter();
  }

  @Override
  public ServletOutputStream getOutputStream() throws IOException {
    // System.out.println("getOutputStream");
    if (getIsJson()) {
      useOutput = true;
      return servletOutputStream;
    }
    return super.getOutputStream();
  }

  @Override
  public void flushBuffer() throws IOException {
    if (getIsJson()) {
      // Print the JSON response body
      // System.out.println("JSON Response Body: " + charArrayWriter.toString());
      PrintWriter responseWriter = super.getWriter();
      responseWriter.write(charArrayWriter.toString());
      responseWriter.flush();
    }
  }

  public String getWriteJsonString() {
    if (getIsJson()) {
      return charArrayWriter.toString();
    }
    return "";
  }

  @Override
  public void setContentType(String type) {
    if (type.contains("application/json")) {
      // System.out.println("set json");
      isJson = true;
    }
    super.setContentType(type);
  }

  @Override
  public String getContentType() {
    // System.out.println("getContentType");
    return super.getContentType();
  }

  @Override
  public void setHeader(String name, String value) {
    // System.out.println("set header: "+name+" value: "+value);
    super.setHeader(name, value);
  }

  @Override
  public void finalize() {
    try {
      flushBuffer();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  public Boolean getIsJson() {
    if (this.getContentType().contains("application/json")) {
      // System.out.println("isJson return true");
      isJson = true;
    }

    return isJson;
  }

  public Boolean getUseWrite() {
    return useWrite;
  }

  public Boolean getUseOutput() {
    return useOutput;
  }

  public String flushStreamBuffer() throws IOException {
    if (getIsJson()) {
      byte[] bts = byteArrayOutputStream.toByteArray();
      String str = new String(bts, "UTF-8");
      //System.out.println("----- output and bts toString = " + str);
      // 写到客户端
      // Finally, copy the captured data to the actual response

      ServletOutputStream out = getResponse().getOutputStream();
      out.write(bts);
      out.flush();

      return str;
    }

    return "";
  }

  class CustomServletOutputStream extends ServletOutputStream {

    private PrintStream printStream;

    public CustomServletOutputStream(PrintStream printStream) {
      this.printStream = printStream;
    }

    @Override
    public boolean isReady() {
      return true;
    }

    @Override
    public void setWriteListener(WriteListener writeListener) {
      // No implementation needed
    }

    @Override
    public void write(int b) throws IOException {
      printStream.write(b);
    }
  }
}
