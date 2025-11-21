package datadog.trace.logging;

import datadog.environment.OperatingSystem;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;

public class PrintStreamWrapper extends PrintStream {
  private static final int LINE_SEPARATOR_LENGTH = OperatingSystem.isWindows() ? 2 : 1;
  private volatile boolean captureOutput = false;
  private volatile int currentSize = 0;
  private PrintStream capturingStream = null;

  public PrintStreamWrapper(PrintStream ps) {
    super(ps);
  }

  // use for tests only
  public OutputStream getOriginalPrintStream() {
    return super.out;
  }

  @Override
  public void println(String x) {
    super.println(x); // log as usual
    if (captureOutput) {
      int outputLength = x.length() + LINE_SEPARATOR_LENGTH;
      if (currentSize + outputLength < LogReporter.MAX_LOGFILE_SIZE_BYTES) {
        synchronized (this) {
          capturingStream.println(x);
          currentSize += outputLength;
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

  public void startCapturing(Path filepath) {
    stopCapturing();
    try {
      if (filepath != null) {
        String logFile = filepath.toString();
        FileOutputStream fileOutputStream = new FileOutputStream(logFile);
        capturingStream = new PrintStream(fileOutputStream, true);
        captureOutput = true;
      }
    } catch (FileNotFoundException e) {
      // TODO maybe have support for delayed logging of early failures?
    }
  }

  public void stopCapturing() {
    if (capturingStream != null) {
      capturingStream.close();
      capturingStream = null;
    }
    currentSize = 0;
    captureOutput = false;
  }
}
