package datadog.smoke;

import static org.junit.Assert.assertEquals;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 34, manifest = Config.NONE)
public class AndroidJUnit4RunnerTest {

  @Test
  public void test_androidjunit4_runner() {
    assertEquals(34, RuntimeEnvironment.getApiLevel());
  }
}
