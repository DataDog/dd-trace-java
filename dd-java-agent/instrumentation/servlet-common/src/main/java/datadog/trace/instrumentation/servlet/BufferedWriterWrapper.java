package datadog.trace.instrumentation.servlet;

import datadog.trace.api.http.StoredCharBody;
import java.io.PrintWriter;
import java.util.Locale;

public class BufferedWriterWrapper extends PrintWriter {
  private final PrintWriter writer;
  private final StoredCharBody storedCharBody;

  public BufferedWriterWrapper(PrintWriter writer, StoredCharBody storedCharBody) {
    super(writer);
    this.writer = writer;
    this.storedCharBody = storedCharBody;
  }

  @Override
  public void write(int c) {
    storedCharBody.appendData(c);
    writer.write(c);
  }

  @Override
  public void write(char[] buf, int off, int len) {
    storedCharBody.appendData(buf, off, off + len);
    writer.write(buf, off, len);
  }

  @Override
  public void write(String s, int off, int len) {
    storedCharBody.appendData(s.substring(off, off + len));
    writer.write(s, off, len);
  }

  @Override
  public void write(String s) {
    storedCharBody.appendData(s);
    writer.write(s);
  }

  @Override
  public void write(char[] buf) {
    storedCharBody.appendData(buf, 0, buf.length);
    writer.write(buf);
  }

  @Override
  public void print(boolean b) {
    String s = String.valueOf(b);
    storedCharBody.appendData(s);
    writer.print(b);
  }

  @Override
  public void print(char c) {
    storedCharBody.appendData(c);
    writer.print(c);
  }

  @Override
  public void print(int i) {
    String s = String.valueOf(i);
    storedCharBody.appendData(s);
    writer.print(i);
  }

  @Override
  public void print(long l) {
    String s = String.valueOf(l);
    storedCharBody.appendData(s);
    writer.print(l);
  }

  @Override
  public void print(float f) {
    String s = String.valueOf(f);
    storedCharBody.appendData(s);
    writer.print(f);
  }

  @Override
  public void print(double d) {
    String s = String.valueOf(d);
    storedCharBody.appendData(s);
    writer.print(d);
  }

  @Override
  public void print(char[] s) {
    storedCharBody.appendData(s, 0, s.length);
    writer.print(s);
  }

  @Override
  public void print(String s) {
    storedCharBody.appendData(s);
    writer.print(s);
  }

  @Override
  public void print(Object obj) {
    String s = String.valueOf(obj);
    storedCharBody.appendData(s);
    writer.print(obj);
  }

  @Override
  public void println() {
    storedCharBody.appendData('\n');
    writer.println();
  }

  @Override
  public void println(boolean x) {
    String s = String.valueOf(x);
    storedCharBody.appendData(s);
    storedCharBody.appendData('\n');
    writer.println(x);
  }

  @Override
  public void println(char x) {
    storedCharBody.appendData(x);
    storedCharBody.appendData('\n');
    writer.println(x);
  }

  @Override
  public void println(int x) {
    String s = String.valueOf(x);
    storedCharBody.appendData(s);
    storedCharBody.appendData('\n');
    writer.println(x);
  }

  @Override
  public void println(long x) {
    String s = String.valueOf(x);
    storedCharBody.appendData(s);
    storedCharBody.appendData('\n');
    writer.println(x);
  }

  @Override
  public void println(float x) {
    String s = String.valueOf(x);
    storedCharBody.appendData(s);
    storedCharBody.appendData('\n');
    writer.println(x);
  }

  @Override
  public void println(double x) {
    String s = String.valueOf(x);
    storedCharBody.appendData(s);
    storedCharBody.appendData('\n');
    writer.println(x);
  }

  @Override
  public void println(char[] x) {
    storedCharBody.appendData(x, 0, x.length);
    storedCharBody.appendData('\n');
    writer.println(x);
  }

  @Override
  public void println(String x) {
    storedCharBody.appendData(x);
    storedCharBody.appendData('\n');
    writer.println(x);
  }

  @Override
  public void println(Object x) {
    String s = String.valueOf(x);
    storedCharBody.appendData(s);
    storedCharBody.appendData('\n');
    writer.println(x);
  }

  @Override
  public PrintWriter printf(String format, Object... args) {
    String s = String.format(format, args);
    storedCharBody.appendData(s);
    return writer.printf(format, args);
  }

  @Override
  public PrintWriter printf(Locale l, String format, Object... args) {
    String s = String.format(l, format, args);
    storedCharBody.appendData(s);
    return writer.printf(l, format, args);
  }

  @Override
  public PrintWriter format(String format, Object... args) {
    String s = String.format(format, args);
    storedCharBody.appendData(s);
    return writer.format(format, args);
  }

  @Override
  public PrintWriter format(Locale l, String format, Object... args) {
    String s = String.format(l, format, args);
    storedCharBody.appendData(s);
    return writer.format(l, format, args);
  }

  @Override
  public PrintWriter append(CharSequence csq) {
    storedCharBody.appendData(csq.toString());
    return writer.append(csq);
  }

  @Override
  public PrintWriter append(CharSequence csq, int start, int end) {
    storedCharBody.appendData(csq.subSequence(start, end).toString());
    return writer.append(csq, start, end);
  }

  @Override
  public PrintWriter append(char c) {
    storedCharBody.appendData(c);
    return writer.append(c);
  }

  @Override
  public void flush() {
    writer.flush();
    storedCharBody.maybeNotifyAndBlock();
  }

  @Override
  public void close() {
    writer.close();
    storedCharBody.maybeNotifyAndBlock();
  }

  @Override
  public boolean checkError() {
    return writer.checkError();
  }
}
