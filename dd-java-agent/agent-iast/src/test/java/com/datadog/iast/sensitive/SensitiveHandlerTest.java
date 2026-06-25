package com.datadog.iast.sensitive;

import static datadog.trace.api.ConfigDefaults.DEFAULT_IAST_REDACTION_NAME_PATTERN;
import static datadog.trace.api.ConfigDefaults.DEFAULT_IAST_REDACTION_VALUE_PATTERN;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.iast.sensitive.SensitiveHandler.Tokenizer;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

/** Most of the testing is done via {@link com.datadog.iast.model.json.EvidenceRedactionTest}. */
class SensitiveHandlerTest {

  // Valid under java.util.regex (named group + backreference) but rejected by RE2J, so
  // SensitiveHandlerImpl must fall back to the default pattern instead of failing to compile.
  private static final String RE2J_INCOMPATIBLE_PATTERN = "(?<g>secret)\\k<g>";

  @Test
  void emptyTokenizerReturnsNothing() {
    final Tokenizer tokenizer = Tokenizer.EMPTY;
    assertFalse(tokenizer.next());
    assertThrows(NoSuchElementException.class, tokenizer::current);
  }

  @Test
  void currentInstanceHasValue() {
    assertNotNull(SensitiveHandler.get());
  }

  @Test
  void incompatibleNamePatternFallsBackToDefault() {
    final SensitiveHandlerImpl handler =
        new SensitiveHandlerImpl(RE2J_INCOMPATIBLE_PATTERN, DEFAULT_IAST_REDACTION_VALUE_PATTERN);

    // the default name pattern is used instead of failing to compile
    assertTrue(handler.isSensitiveName("password"));
    assertFalse(handler.isSensitiveName("username"));
  }

  @Test
  void incompatibleValuePatternFallsBackToDefault() {
    final SensitiveHandlerImpl handler =
        new SensitiveHandlerImpl(DEFAULT_IAST_REDACTION_NAME_PATTERN, RE2J_INCOMPATIBLE_PATTERN);

    // the default value pattern is used instead of failing to compile
    assertTrue(handler.isSensitiveValue("bearer abc123def456"));
    assertFalse(handler.isSensitiveValue("not a secret value"));
  }
}
