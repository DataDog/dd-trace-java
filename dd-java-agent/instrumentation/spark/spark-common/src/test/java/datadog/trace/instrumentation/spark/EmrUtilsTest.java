package datadog.trace.instrumentation.spark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.spark.SparkConf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EmrUtilsTest {

  private String originalUserDir;

  @BeforeEach
  void saveUserDir() {
    originalUserDir = System.getProperty("user.dir");
  }

  @AfterEach
  void restoreUserDir() {
    System.setProperty("user.dir", originalUserDir);
  }

  @Test
  void returnsStepIdWhenWorkdirMatchesEmrPattern() {
    System.setProperty("user.dir", "/mnt/var/lib/hadoop/steps/s-07767992IY7VC5NVV854");
    assertEquals("s-07767992IY7VC5NVV854", EmrUtils.getEmrStepId());
  }

  @Test
  void returnsNullWhenWorkdirDoesNotMatchEmrPattern() {
    System.setProperty("user.dir", "/home/hadoop");
    assertNull(EmrUtils.getEmrStepId());
  }

  @Test
  void returnsNullForApplicationIdWorkdir() {
    System.setProperty("user.dir", "/home/hadoop/application_1234567890_0001");
    assertNull(EmrUtils.getEmrStepId());
  }

  @Test
  void isRunningOnEmrReturnsTrueWhenEmrInternalExtensionsPresent() {
    SparkConf conf = new SparkConf();
    conf.set("spark.sql.emr.internal.extensions", "com.amazonaws.emr.spark.EmrSparkSessionExtensions");
    assertTrue(EmrUtils.isRunningOnEmr(conf));
  }

  @Test
  void isRunningOnEmrReturnsTrueWhenEmrDefaultExecutorCoresPresent() {
    SparkConf conf = new SparkConf();
    conf.set("spark.emr.default.executor.cores", "1");
    assertTrue(EmrUtils.isRunningOnEmr(conf));
  }

}
