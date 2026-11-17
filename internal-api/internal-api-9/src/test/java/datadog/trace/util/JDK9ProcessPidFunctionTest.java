package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
import java.io.OutputStream;
import org.junit.jupiter.api.Test;

class JDK9ProcessPidFunctionTest {

  private final JDK9ProcessPidFunction pidFunction = new JDK9ProcessPidFunction();

  @Test
  void returnsThePidOfTheProcess() {
    Process process =
        new FakeProcess() {
          @Override
          public long pid() {
            return 42;
          }
        };

    assertEquals("42", pidFunction.apply(process));
  }

  @Test
  void returnsEmptyWhenTheProcessCannotTellItsPid() {
    // Process.pid() throws UnsupportedOperationException unless a subclass supports it
    assertEquals("", pidFunction.apply(new FakeProcess()));
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
