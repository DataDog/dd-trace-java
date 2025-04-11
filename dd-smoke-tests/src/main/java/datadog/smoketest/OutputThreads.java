package datadog.smoketest;

import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

public class OutputThreads implements Closeable {
  private static final long THREAD_JOIN_TIMEOUT_MILLIS = 10 * 1000;
  public static final int MAX_LINE_SIZE = 1024 * 1024;

  final ThreadGroup tg = new ThreadGroup("smoke-output");
  final List<String> testLogMessages = new ArrayList<>();

  public void close() {
    tg.interrupt();
    Thread[] threads = new Thread[tg.activeCount()];
    tg.enumerate(threads);

    for (Thread thread : threads) {
      try {
        thread.join(THREAD_JOIN_TIMEOUT_MILLIS);
      } catch (InterruptedException e) {
        // ignore
      }
    }
  }

  class ProcessOutputRunnable implements Runnable {
    final ReadableByteChannel rc;
    ByteBuffer buffer = ByteBuffer.allocate(MAX_LINE_SIZE);
    final WritableByteChannel wc;
    CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();

    ProcessOutputRunnable(InputStream is, File output) throws FileNotFoundException {
      rc = Channels.newChannel(is);
      wc = Channels.newChannel(new FileOutputStream(output));
    }

    @Override
    public void run() {
      boolean online = true;
      while (online) {
        // we may have data in the buffer we did not consume for line splitting purposes
        int skip = buffer.position();

        try {
          if (rc.read(buffer) == -1) {
            online = false;
          }
        } catch (IOException ioe) {
          online = false;
        }

        buffer.flip();
        // write to log file
        try {
          wc.write((ByteBuffer) buffer.duplicate().position(skip));
        } catch (IOException e) {
          System.out.println("ERROR WRITING TO LOG FILE: " + e.getMessage());
          e.printStackTrace();
          return;
        }

        // subBuff will always start at the beginning of the next (potential) line
        ByteBuffer subBuff = buffer.duplicate();
        int consumed = 0;
        while (true) {
          boolean hasRemaining = subBuff.hasRemaining();
          if (hasRemaining) {
            int c = subBuff.get();
            if (c != '\n' && c != '\r') {
              continue;
            }
            // found line end
          } else if (online && consumed > 0) {
            break;
            // did not find line end, but we already consumed a line
            // save the data for the next read iteration
          } // else we did not consume any line, or there will be no further reads.
          // Treat the buffer as single line despite lack of terminator

          consumed += subBuff.position();
          String line = null;
          try {
            line = decoder.decode((ByteBuffer) subBuff.duplicate().flip()).toString().trim();
          } catch (CharacterCodingException e) {
            throw new RuntimeException(e);
          }

          if (!line.isEmpty()) {
            synchronized (testLogMessages) {
              testLogMessages.add(line);
              testLogMessages.notifyAll();
            }
          }

          if (hasRemaining) {
            subBuff = subBuff.slice();
          } else {
            break;
          }
        }

        buffer.position(consumed);
        buffer.compact();
      }
    }
  }

  public void captureOutput(Process p, File outputFile) throws FileNotFoundException {
    new Thread(tg, new ProcessOutputRunnable(p.getInputStream(), outputFile)).start();
  }

  /**
   * Tries to find a log line that matches the given predicate. After reading all the log lines
   * already collected, it will wait up to 5 seconds for a new line matching the predicate.
   *
   * @param checker should return true if a match is found
   */
  public boolean processTestLogLines(Function<String, Boolean> checker) throws TimeoutException {
    int l = 0;
    long waitStart = 0;

    while (true) {
      String msg;
      synchronized (testLogMessages) {
        if (l >= testLogMessages.size()) {
          long waitTime;
          if (waitStart != 0) {
            waitTime = 10000 - (System.currentTimeMillis() - waitStart);
            if (waitTime < 0) {
              throw new TimeoutException();
            }
          } else {
            waitStart = System.currentTimeMillis();
            waitTime = 10000;
          }
          try {
            testLogMessages.wait(waitTime);
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
        }
        if (l >= testLogMessages.size()) {
          throw new TimeoutException();
        }
        // the array is only cleared at the end of the test, so index l exists
        msg = testLogMessages.get(l++);
      }

      if (checker.apply(msg)) {
        return true;
      }
    }
  }

  public void clearMessages() {
    synchronized (testLogMessages) {
      testLogMessages.clear();
    }
  }
}
