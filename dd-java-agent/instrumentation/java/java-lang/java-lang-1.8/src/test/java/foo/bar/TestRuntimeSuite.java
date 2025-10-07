package foo.bar;

import java.io.File;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestRuntimeSuite {

  private static final Logger LOGGER = LoggerFactory.getLogger(TestRuntimeSuite.class);

  private final Runtime runtime;

  public TestRuntimeSuite(final Runtime runtime) {
    this.runtime = runtime;
  }

  public Process exec(final String command) throws IOException {
    LOGGER.debug("Before runtime exec {}", command);
    final Process result = runtime.exec(command);
    LOGGER.debug("After runtime exec {}", result);
    return result;
  }

  public Process exec(final String[] command) throws IOException {
    LOGGER.debug("Before runtime exec {}", (Object) command);
    final Process result = runtime.exec(command);
    LOGGER.debug("After runtime exec {}", result);
    return result;
  }

  public Process exec(final String command, final String[] envp) throws IOException {
    LOGGER.debug("Before runtime exec {} {}", command, envp);
    final Process result = runtime.exec(command, envp);
    LOGGER.debug("After runtime exec {}", result);
    return result;
  }

  public Process exec(final String[] command, final String[] envp) throws IOException {
    LOGGER.debug("Before runtime exec {} {}", command, envp);
    final Process result = runtime.exec(command, envp);
    LOGGER.debug("After runtime exec {}", result);
    return result;
  }

  public Process exec(final String command, final String[] envp, final File file)
      throws IOException {
    LOGGER.debug("Before runtime exec {} {} {}", command, envp, file);
    final Process result = runtime.exec(command, envp, file);
    LOGGER.debug("After runtime exec {}", result);
    return result;
  }

  public Process exec(final String[] command, final String[] envp, final File file)
      throws IOException {
    LOGGER.debug("Before runtime exec {} {} {}", command, envp, file);
    final Process result = runtime.exec(command, envp, file);
    LOGGER.debug("After runtime exec {}", result);
    return result;
  }
}
