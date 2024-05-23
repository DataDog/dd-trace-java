package datadog.trace.instrumentation.servlet3;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import java.io.IOException;
import java.io.*;
import java.nio.charset.Charset;

public class BodyReaderHttpServletRequestWrapper extends HttpServletRequestWrapper {


  private String bodyStr;

  private final byte[] body;

  public BodyReaderHttpServletRequestWrapper(HttpServletRequest request) throws IOException {
    super(request);
    this.bodyStr = getBodyString(request);
    body = bodyStr.getBytes();
  }

  public String getBodyStr() {
    return bodyStr;
  }

  @Override
  public ServletInputStream getInputStream() throws IOException {
    final ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(body);
    return new ServletInputStream() {
      @Override
      public int read() throws IOException {
        return byteArrayInputStream.read();
      }

      @Override
      public boolean isFinished() {
        return false;
      }

      @Override
      public boolean isReady() {
        return false;
      }

      @Override
      public void setReadListener(ReadListener readListener) {

      }
    };
  }


  public  String getBodyString(HttpServletRequest request) throws IOException {
    StringBuilder sb = new StringBuilder();
    InputStream inputStream = null;
    BufferedReader reader = null;
    try {
      inputStream = request.getInputStream();
      reader = new BufferedReader(
          new InputStreamReader(inputStream, Charset.forName("UTF-8")));

      char[] bodyCharBuffer = new char[1024];
      int len = 0;
      while ((len = reader.read(bodyCharBuffer)) != -1) {
        sb.append(new String(bodyCharBuffer, 0, len));
      }
    } catch (IOException e) {
      e.printStackTrace();
    } finally {
      if (inputStream != null) {
        try {
          inputStream.close();
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
      if (reader != null) {
        try {
          reader.close();
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    }
    return sb.toString();
  }

  @Override
  public BufferedReader getReader() throws IOException {
    return new BufferedReader(new InputStreamReader(this.getInputStream()));
  }
}
