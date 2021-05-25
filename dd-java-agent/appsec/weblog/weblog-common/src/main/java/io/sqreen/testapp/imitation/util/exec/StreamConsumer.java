package io.sqreen.testapp.imitation.util.exec;

import com.google.common.io.ByteStreams;
import com.google.common.io.Closeables;
import java.io.IOException;
import java.io.InputStream;

/**
 * A stream consumer which is run in parallel to the command (process) being executed. It is
 * necessary to avoid the situation when the process hang up when it's STDERR/STDOUT is buffered by
 * OS.
 */
public class StreamConsumer extends Thread {

  private InputStream is;

  public StreamConsumer(InputStream is, String name) {
    this.is = is;
    setDaemon(true);
    setName(name);
  }

  public void run() {
    try {
      // Just copy the stream to NULL
      ByteStreams.copy(is, ByteStreams.nullOutputStream());
    } catch (IOException ioe) {
      throw new RuntimeException(ioe);
    } finally {
      Closeables.closeQuietly(is);
    }
  }
}
