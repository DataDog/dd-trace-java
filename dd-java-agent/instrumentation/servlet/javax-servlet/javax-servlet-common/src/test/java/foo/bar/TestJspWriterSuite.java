package foo.bar;

import java.io.IOException;
import javax.servlet.jsp.JspWriter;

public class TestJspWriterSuite {

  JspWriter writer;

  public TestJspWriterSuite(final JspWriter writer) {
    this.writer = writer;
  }

  public void printlnTest(char[] x) throws IOException {
    writer.println(x);
  }

  public void printlnTest(String x) throws IOException {
    writer.println(x);
  }

  public void printTest(char[] s) throws IOException {
    writer.print(s);
  }

  public void printTest(String s) throws IOException {
    writer.print(s);
  }

  public void write(char[] s) throws IOException {
    writer.write(s);
  }

  public void write(String s) throws IOException {
    writer.write(s);
  }
}
