package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConfigStringsTest {

  /** Dotted capital I (U+0130) that a Turkish-locale {@code toUpperCase()} produces from 'i'. */
  private static final char DOTTED_CAPITAL_I = 'İ';

  private Locale previousDefault;

  @BeforeEach
  void forceTurkishLocale() {
    // Turkish is the locale where a locale-sensitive toUpperCase() maps 'i' -> 'İ' (U+0130) and
    // toLowerCase() maps 'I' -> 'ı' (U+0131, dotless). That is exactly what corrupted the config
    // telemetry payload. Forcing it as the default locale here proves the conversions are
    // locale-independent (pinned to Locale.ROOT) rather than relying on the machine's locale.
    previousDefault = Locale.getDefault();
    Locale.setDefault(new Locale("tr", "TR"));
  }

  @AfterEach
  void restoreLocale() {
    Locale.setDefault(previousDefault);
  }

  @Test
  void toEnvVarUppercasesLowerIToAsciiIOnTurkishLocale() {
    String result = ConfigStrings.toEnvVar("dd.profiling.i");

    // Must be the plain ASCII 'I' (U+0049), not the Turkish dotted 'İ' (U+0130).
    assertEquals("DD_PROFILING_I", result);
    assertFalse(
        result.indexOf(DOTTED_CAPITAL_I) >= 0,
        "env var name must not contain the dotted capital I (U+0130)");
  }
}
