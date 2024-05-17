package datadog.trace.logging;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;

public class PrintStreamWrapper extends PrintStream {
  private static final int MAX_LOGFILE_SIZE_MB = 15;

  private static final int MAX_LOGFILE_SIZE_BYTES = MAX_LOGFILE_SIZE_MB << 20;
  private static final int lineSeparatorLength =
      System.getProperty("line.separator").getBytes().length;
  private static final byte[] lineSeparatorBytes = System.getProperty("line.separator").getBytes();
  private static boolean activate = false;
  private static PrintStream printStream;
  private static ByteArrayOutputStream byteArrayOutputStream;
  private static int currentSize = 0;

  public PrintStreamWrapper(OutputStream out) {
    super(out);
  }

  @Override
  public void println(String x) {
    super.println(x); // do log as usual according to customer request
    if (activate) {
      synchronized (this) {
        currentSize += x.getBytes().length + lineSeparatorLength;
        if (currentSize < MAX_LOGFILE_SIZE_BYTES) {
          printStream.println(x);
        } else {
          activate = false;
        }
      }
    }
  }

  @Override
  public void println(Object x) {
    String s = String.valueOf(x);
    println(s);
  }

  public static void start() {
    clean();
    byteArrayOutputStream = new ByteArrayOutputStream();
    printStream = new PrintStream(byteArrayOutputStream, true);
    activate = true;
  }

  public static byte[] getBuffer() {
    return byteArrayOutputStream.toByteArray();
  }

  public static void clean() {
    byteArrayOutputStream = null;
    printStream = null;
    activate = false;
    currentSize = 0;
  }
}
