package datadog.trace.logging;

import datadog.trace.api.Platform;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;

public class PrintStreamWrapper extends PrintStream {
  private static final int lineSeparatorLength = Platform.isWindows() ? 2 : 1;
  private boolean captureOutput = false;
  private int currentSize = 0;
  private PrintStream printStream = null;

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
    if (captureOutput) {
      currentSize += x.length() + lineSeparatorLength;
      if (currentSize < LogReporter.MAX_LOGFILE_SIZE_BYTES) {
        synchronized (this) {
          printStream.println(x);
        }
      } else {
        captureOutput = false;
      }
    }
  }

  @Override
  public void println(Object x) {
    String s = String.valueOf(x);
    println(s);
  }

  public void start(Path filepath) {
    clean();
    try {
      if (filepath != null) {
        String logFile = filepath.toString();
        FileOutputStream fileOutputStream = new FileOutputStream(logFile);
        printStream = new PrintStream(fileOutputStream, true);
        captureOutput = true;
      }
    } catch (FileNotFoundException e) {
      // TODO maybe have support for delayed logging of early failures?
    }
  }

  public void clean() {
    if (printStream != null) {
      printStream.close();
      printStream = null;
    }
    captureOutput = false;
    currentSize = 0;
  }
}
