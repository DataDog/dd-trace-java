package foo.bar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestMathSuite {

  private static final Logger LOGGER = LoggerFactory.getLogger(TestMathSuite.class);

  public static double random() {
    LOGGER.debug("Before random");
    final double result = Math.random();
    LOGGER.debug("After random {}", result);
    return result;
  }
}
