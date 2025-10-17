package datadog.crashtracking.dto;

import static org.junit.jupiter.api.Assertions.*;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SemanticVersionTest {

  @ParameterizedTest
  @MethodSource("argsForSemanticVersionParse")
  public void testSemanticVersionParse(String version, SemanticVersion expected) {
    assertEquals(expected, SemanticVersion.of(version));
  }

  private static Stream<Arguments> argsForSemanticVersionParse() {
    return Stream.of(
        Arguments.of("1", new SemanticVersion(1, 0, 0)),
        Arguments.of("1.2", new SemanticVersion(1, 2, 0)),
        Arguments.of("1.2.0", new SemanticVersion(1, 2, 0)),
        Arguments.of("3.4.5", new SemanticVersion(3, 4, 5)),
        Arguments.of("1.2.3-ea1", new SemanticVersion(1, 2, 3)),
        Arguments.of("1.2.3.4", new SemanticVersion(1, 2, 3)),
        Arguments.of("something", new SemanticVersion(0, 0, 0)) // invalid
        );
  }
}
