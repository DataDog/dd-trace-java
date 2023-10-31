package datadog.trace.api.profiling;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.InputStream;

public interface ProfilingSnapshot extends ObservableType {
  abstract class RecordingStream extends InputStream {
    abstract public boolean isEmpty();
  }

  @Nonnull
  <T extends RecordingStream> T getStream() throws IOException;

  enum Kind {
    PERIODIC,
    ON_SHUTDOWN
  }
}
