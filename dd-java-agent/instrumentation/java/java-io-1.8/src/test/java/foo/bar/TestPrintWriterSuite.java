package foo.bar;

import java.io.PrintWriter;
import java.util.Locale;

public class TestPrintWriterSuite {

  PrintWriter pw;

  public TestPrintWriterSuite(final PrintWriter pw) {
    this.pw = pw;
  }

  public void write(String s, int off, int len) {
    pw.write(s, off, len);
  }

  public void write(String s) {
    pw.write(s);
  }

  public void write(char buf[], int off, int len) {
    pw.write(buf, off, len);
  }

  public void write(char buf[]) {
    pw.write(buf);
  }

  public PrintWriter format(Locale l, String format, Object... args) {
    return pw.format(l, format, args);
  }

  public PrintWriter format(String format, Object... args) {
    return pw.format(format, args);
  }

  public PrintWriter testPrintf(String format, Object... args) {
    return pw.printf(format, args);
  }

  public PrintWriter testPrintf(Locale l, String format, Object... args) {
    return pw.printf(l, format, args);
  }

  public void println(char x[]) {
    pw.println(x);
  }

  public void println(String x) {
    pw.println(x);
  }

  public void print(char s[]) {
    pw.print(s);
  }

  public void print(String s) {
    pw.print(s);
  }
}
