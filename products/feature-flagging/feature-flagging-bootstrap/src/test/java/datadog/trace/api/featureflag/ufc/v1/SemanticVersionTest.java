package datadog.trace.api.featureflag.ufc.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SemanticVersionTest {

  @Test
  void ignoresBuildMetadataForPrecedence() {
    assertEquals(
        0,
        SemanticVersion.parse("4.5.6-rc.1+build.42")
            .compareTo(SemanticVersion.parse("4.5.6-rc.1")));
  }

  @Test
  void comparesPrereleaseIdentifiersUsingSemanticVersionPrecedence() {
    assertEquals(
        -1,
        Integer.signum(
            SemanticVersion.parse("1.2.3-alpha.4")
                .compareTo(SemanticVersion.parse("1.2.3-alpha.5"))));
    assertEquals(
        -1,
        Integer.signum(
            SemanticVersion.parse("1.2.3-alpha.5").compareTo(SemanticVersion.parse("1.2.3"))));
  }

  @Test
  void acceptsCommonMaximumAndRejectsOverflowingCoreComponent() {
    SemanticVersion.parse("9007199254740991.9007199254740991.9007199254740991");

    assertThrows(
        IllegalArgumentException.class, () -> SemanticVersion.parse("18446744073709551616.0.0"));
  }
}
