package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
import java.io.OutputStream;
import org.junit.jupiter.api.Test;

/** Covers {@link PidHelper#getPid(Process)} and its cached {@code Process.pid()} handle. */
class PidHelperChildPidTest {

  @Test
  void returnsThePidOfTheProcess() {
    Process process =
        new FakeProcess() {
          @Override
          public long pid() {
            return 42;
          }
        };

    assertEquals("42", PidHelper.getPid(process));
  }

  @Test
  void returnsEmptyWhenTheProcessCannotTellItsPid() {
    // Process.pid() throws UnsupportedOperationException unless a subclass supports it
    assertEquals("", PidHelper.getPid(new FakeProcess()));
  }

  private static class FakeProcess extends Process {
    @Override
    public OutputStream getOutputStream() {
      return null;
    }

    @Override
    public InputStream getInputStream() {
      return null;
    }

    @Override
    public InputStream getErrorStream() {
      return null;
    }

    @Override
    public int waitFor() {
      return 0;
    }

    @Override
    public int exitValue() {
      return 0;
    }

    @Override
    public void destroy() {}
  }
}
