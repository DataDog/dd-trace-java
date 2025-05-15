package datadog.trace.instrumentation.servlet;

import datadog.trace.api.http.StoredByteBody;
import java.io.IOException;
import javax.servlet.ServletOutputStream;

public abstract class AbstractServletOutputStreamWrapper extends ServletOutputStream {
  protected final ServletOutputStream os;
  private final StoredByteBody storedByteBody;

  public AbstractServletOutputStreamWrapper(ServletOutputStream os, StoredByteBody storedByteBody) {
    this.os = os;
    this.storedByteBody = storedByteBody;
  }

  @Override
  public void write(byte[] b) throws IOException {
    storedByteBody.appendData(b, 0, b.length);
    os.write(b);
  }

  @Override
  public void write(byte[] b, int off, int len) throws IOException {
    storedByteBody.appendData(b, off, off + len);
    os.write(b, off, len);
  }

  @Override
  public void write(int b) throws IOException {
    storedByteBody.appendData(b);
    os.write(b);
  }

  @Override
  public void flush() throws IOException {
    os.flush();
    storedByteBody.maybeNotifyAndBlock();
  }

  @Override
  public void close() throws IOException {
    os.close();
    storedByteBody.maybeNotifyAndBlock();
  }

  @Override
  public void print(String s) throws IOException {
    if (s == null) {
      s = "null";
    }
    byte[] bytes = s.getBytes();
    storedByteBody.appendData(bytes, 0, bytes.length);
    os.print(s);
  }

  @Override
  public void print(boolean b) throws IOException {
    String s = String.valueOf(b);
    byte[] bytes = s.getBytes();
    storedByteBody.appendData(bytes, 0, bytes.length);
    os.print(b);
  }

  @Override
  public void print(char c) throws IOException {
    String s = String.valueOf(c);
    byte[] bytes = s.getBytes();
    storedByteBody.appendData(bytes, 0, bytes.length);
    os.print(c);
  }

  @Override
  public void print(int i) throws IOException {
    String s = String.valueOf(i);
    byte[] bytes = s.getBytes();
    storedByteBody.appendData(bytes, 0, bytes.length);
    os.print(i);
  }

  @Override
  public void print(long l) throws IOException {
    String s = String.valueOf(l);
    byte[] bytes = s.getBytes();
    storedByteBody.appendData(bytes, 0, bytes.length);
    os.print(l);
  }

  @Override
  public void print(float f) throws IOException {
    String s = String.valueOf(f);
    byte[] bytes = s.getBytes();
    storedByteBody.appendData(bytes, 0, bytes.length);
    os.print(f);
  }

  @Override
  public void print(double d) throws IOException {
    String s = String.valueOf(d);
    byte[] bytes = s.getBytes();
    storedByteBody.appendData(bytes, 0, bytes.length);
    os.print(d);
  }

  @Override
  public void println() throws IOException {
    byte[] bytes = "\n".getBytes();
    storedByteBody.appendData(bytes, 0, bytes.length);
    os.println();
  }

  @Override
  public void println(String s) throws IOException {
    if (s == null) {
      s = "null";
    }
    String withNewline = s + "\n";
    byte[] bytes = withNewline.getBytes();
    storedByteBody.appendData(bytes, 0, bytes.length);
    os.println(s);
  }

  @Override
  public void println(boolean b) throws IOException {
    String s = b + "\n";
    byte[] bytes = s.getBytes();
    storedByteBody.appendData(bytes, 0, bytes.length);
    os.println(b);
  }

  @Override
  public void println(char c) throws IOException {
    String s = c + "\n";
    byte[] bytes = s.getBytes();
    storedByteBody.appendData(bytes, 0, bytes.length);
    os.println(c);
  }

  @Override
  public void println(int i) throws IOException {
    String s = i + "\n";
    byte[] bytes = s.getBytes();
    storedByteBody.appendData(bytes, 0, bytes.length);
    os.println(i);
  }

  @Override
  public void println(long l) throws IOException {
    String s = l + "\n";
    byte[] bytes = s.getBytes();
    storedByteBody.appendData(bytes, 0, bytes.length);
    os.println(l);
  }

  @Override
  public void println(float f) throws IOException {
    String s = f + "\n";
    byte[] bytes = s.getBytes();
    storedByteBody.appendData(bytes, 0, bytes.length);
    os.println(f);
  }

  @Override
  public void println(double d) throws IOException {
    String s = d + "\n";
    byte[] bytes = s.getBytes();
    storedByteBody.appendData(bytes, 0, bytes.length);
    os.println(d);
  }
}
