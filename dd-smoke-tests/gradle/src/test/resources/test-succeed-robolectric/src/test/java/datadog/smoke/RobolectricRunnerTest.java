package datadog.smoke;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class RobolectricRunnerTest {

  @Test
  public void test_robolectric_runner() {
    assertEquals(34, RuntimeEnvironment.getApiLevel());
  }
}
