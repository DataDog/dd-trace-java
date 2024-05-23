package datadog.trace.logging;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import datadog.trace.api.Config;

public class PrintStreamWrapper extends PrintStream {
  private static final int MAX_LOGFILE_SIZE_MB = 15;
  private static final int MAX_LOGFILE_SIZE_BYTES = MAX_LOGFILE_SIZE_MB << 20;

  private static final String lineSeparator = System.getProperty("line.separator");
  private static final byte[] lineSeparatorBytes = lineSeparator.getBytes();
  private static final int lineSeparatorLength = lineSeparatorBytes.length;

  private static boolean activate = false;
  private static int currentSize = 0;
  private static PrintStream printStream = null;
  // private static ByteArrayOutputStream byteArrayOutputStream = null;
  private static FileOutputStream fileOutputStream = null;
  private static String logFile = null;

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

  public static void start(String filepath) {
    //
    //Config.get().getAppSecTraceRateLimit();
    clean();
    logFile = filepath;
    try {
      if (logFile != null) {
        fileOutputStream = new FileOutputStream(logFile);
        printStream = new PrintStream(fileOutputStream, true);
        activate = true;
      } /*else {
          byteArrayOutputStream = new ByteArrayOutputStream(MAX_LOGFILE_SIZE_BYTES);
          printStream = new PrintStream(byteArrayOutputStream, true);
        }*/
    } catch (FileNotFoundException e) {
      // byteArrayOutputStream = new ByteArrayOutputStream(MAX_LOGFILE_SIZE_BYTES);
      //  printStream = new PrintStream(byteArrayOutputStream, true);
    }

    // activate = true;
  }

  /*public static byte[] getBuffer() {
    if (byteArrayOutputStream != null) {
      return byteArrayOutputStream.toByteArray();
    }
    return null;
  }*/

  public static void clean() {

    if (printStream != null) {
      printStream.close();
      printStream = null;
    }
    // byteArrayOutputStream = null;
    fileOutputStream = null;

    logFile = null;
    activate = false;
    currentSize = 0;
  }
}
