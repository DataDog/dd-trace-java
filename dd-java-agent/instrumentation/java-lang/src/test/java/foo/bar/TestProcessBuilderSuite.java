package foo.bar;

import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestProcessBuilderSuite {

  private static final Logger LOGGER = LoggerFactory.getLogger(TestProcessBuilderSuite.class);

  public static Process start(final List<String> command) throws IOException {
    LOGGER.debug("Before process builder start {}", command);
    final Process result = new ProcessBuilder(command).start();
    LOGGER.debug("After process builder init {}", result);
    return result;
  }
}
