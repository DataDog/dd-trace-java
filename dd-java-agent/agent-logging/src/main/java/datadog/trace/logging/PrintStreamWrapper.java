package datadog.trace.logging;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;

public class PrintStreamWrapper extends PrintStream {
  private static final String lineSeparator = System.getProperty("line.separator");
  private static final byte[] lineSeparatorBytes = lineSeparator.getBytes();
  private static final int lineSeparatorLength = lineSeparatorBytes.length;

  private static boolean activate = false;
  private static int currentSize = 0;
  private static PrintStream printStream = null;
  private static FileOutputStream fileOutputStream = null;
  private static String logFile = null;

  public PrintStreamWrapper(PrintStream ps) {
    super(ps);
  }
  // use for tests only
  public OutputStream getMainPrintStream() {
    return super.out;
  }

  @Override
  public void println(String x) {
    super.println(x); // log as usual
    if (activate) {
      currentSize += x.getBytes().length + lineSeparatorLength;
      if (currentSize < LogReporter.MAX_LOGFILE_SIZE_BYTES) {
        synchronized (this) {
          printStream.println(x);
        }
      } else {
        activate = false;
      }
    }
  }

  @Override
  public void println(Object x) {
    String s = String.valueOf(x);
    println(s);
  }

  public static void start(String filepath) {
    clean();
    logFile = filepath;
    try {
      if (logFile != null) {

        fileOutputStream = new FileOutputStream(logFile);
        printStream = new PrintStream(fileOutputStream, true);
        activate = true;
      }
    } catch (FileNotFoundException e) {
      // TODO maybe have support for delayed logging of early failures?
    }
  }

  public static void clean() {

    if (printStream != null) {
      printStream.close();
      printStream = null;
    }
    fileOutputStream = null;
    logFile = null;
    activate = false;
    currentSize = 0;
  }
}
