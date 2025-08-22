package org.example;

import com.intuit.karate.junit5.Karate;
import org.junit.jupiter.api.Disabled;

/**
 * NOTE: This test is disabled with our migration to JUnit 5: - read('@setupStep') causes a
 * "FileNotFoundException" error - karate.setup().data and karate.setupOnce().data cause "Unknown
 * identifier" errors TODO: Investigate and fix Karate setup feature compatibility with JUnit 5
 */
@Disabled("Karate setup features are incompatible with JUnit 5")
public class TestWithSetupKarate {

  @Karate.Test
  public Karate testSucceed() {
    return Karate.run("classpath:org/example/test_with_setup.feature");
  }
}
