package datadog.smoketest;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

public class IbmDumpTest {
  private static final Random rnd = new Random();

  @Test
  void testIbmDump() throws InterruptedException {
    // Emulate hanged process for 21 minute.
    Thread.sleep(1_000 * 60 * 21);

    int a = rnd.nextInt(10) + 10;
    assertTrue(a > 1);
  }
}
