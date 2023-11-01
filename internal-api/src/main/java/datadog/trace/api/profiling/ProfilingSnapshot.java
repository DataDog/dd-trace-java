package datadog.trace.api.profiling;

import javax.annotation.Nonnull;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;

public interface ProfilingSnapshot extends ObservableType {
  class RecordingStream extends BufferedInputStream {
    public RecordingStream(InputStream in) {
      super(in);
    }
    /**
     * Checks whether the recording input stream does not contain any data
     *
     * @return {@literal true} if the input data stream is empty
     * @throws IOException
     */
    public boolean isEmpty() throws IOException {
      // if 'pos' is non-zero the stream is definitely not empty
      if (pos == 0) {
        try {
          // store the current position (which should be 0)
          mark(1);
          // and try reading the next byte - if it fails the stream is empty
          return read() == -1;
        } finally {
          // restore the stream back to the stored position so it can be properly processed if not
          // empty
          reset();
        }
      }
      return false;
    }
  }

  @Nonnull
  RecordingStream getStream() throws IOException;

  enum Kind {
    PERIODIC,
    ON_SHUTDOWN
  }
}
