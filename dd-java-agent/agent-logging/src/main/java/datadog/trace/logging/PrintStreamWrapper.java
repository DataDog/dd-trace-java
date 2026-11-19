package datadog.trace.logging;

import static java.nio.charset.StandardCharsets.UTF_8;

import datadog.trace.api.internal.VisibleForTesting;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;

public class PrintStreamWrapper extends PrintStream {
  private static final int LINE_SEPARATOR_LENGTH = System.lineSeparator().getBytes(UTF_8).length;
  private volatile boolean captureOutput = false;
  private volatile int currentSize = 0;
  private PrintStream capturingStream = null;

  // JDK 18+ inherits the wrapped stream's charset; older JDKs use the JVM default.
  @SuppressForbidden
  public PrintStreamWrapper(PrintStream ps) {
    super(ps);
  }

  @VisibleForTesting
  public OutputStream getOriginalPrintStream() {
    return super.out;
  }

  @Override
  public void println(String x) {
    super.println(x); // log as usual
    if (captureOutput) {
      synchronized (this) {
        int remainingBytes =
            LogReporter.MAX_LOGFILE_SIZE_BYTES - currentSize - LINE_SEPARATOR_LENGTH;
        // Each UTF-16 code unit needs at least one UTF-8 byte.
        if (x.length() >= remainingBytes) {
          captureOutput = false;
          return;
        }

        int outputLength = x.getBytes(UTF_8).length + LINE_SEPARATOR_LENGTH;
        if (currentSize + outputLength < LogReporter.MAX_LOGFILE_SIZE_BYTES) {
          capturingStream.println(x);
          currentSize += outputLength;
        } else {
          captureOutput = false;
        }
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
        capturingStream = new PrintStream(fileOutputStream, true, UTF_8.name());
        captureOutput = true;
      }
    } catch (IOException e) {
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
