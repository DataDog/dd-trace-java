package utils;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;

public class TestHelper {
  public static String getFixtureContent(String fixture) throws IOException, URISyntaxException {
    return new String(Files.readAllBytes(Paths.get(TestHelper.class.getResource(fixture).toURI())));
  }

  public static List<String> getFixtureLines(String fixture) {
    try {
      return Files.readAllLines(Paths.get(TestHelper.class.getResource(fixture).toURI()));
    } catch (Exception e) {
      throw new RuntimeException(e.getMessage(), e);
    }
  }

  public static void setFieldInConfig(Object target, String fieldName, Object value) {
    try {
      Field field = target.getClass().getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(target, value);
    } catch (Throwable e) {
      e.printStackTrace();
    }
  }

  public static void assertWithTimeout(BooleanSupplier predicate, Duration timeout) {
    Duration sleepTime = Duration.ofMillis(10);
    long count = timeout.toMillis() / sleepTime.toMillis();
    while (count-- > 0 && !predicate.getAsBoolean()) {
      try {
        Thread.sleep(sleepTime.toMillis());
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }
    assertTrue(predicate.getAsBoolean());
  }
}
