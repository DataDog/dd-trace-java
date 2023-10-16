package datadog.trace.bootstrap.debugger.util;

import static org.junit.jupiter.api.Assertions.*;

import datadog.trace.api.Config;
import org.junit.jupiter.api.Test;

class RedactionTest {

  @Test
  public void basic() {
    assertFalse(Redaction.isRedactedKeyword(null));
    assertFalse(Redaction.isRedactedKeyword(""));
    assertFalse(Redaction.isRedactedKeyword("foobar"));
    assertFalse(Redaction.isRedactedKeyword("@-_$"));
    assertTrue(Redaction.isRedactedKeyword("password"));
    assertTrue(Redaction.isRedactedKeyword("PassWord"));
    assertTrue(Redaction.isRedactedKeyword("pass-word"));
    assertTrue(Redaction.isRedactedKeyword("_Pass-Word_"));
    assertTrue(Redaction.isRedactedKeyword("$pass_worD"));
    assertTrue(Redaction.isRedactedKeyword("@passWord@"));
  }

  @Test
  public void userDefinedKeywords() {
    final String REDACTED_IDENTIFIERS = "dd.dynamic.instrumentation.redacted.identifiers";
    System.setProperty(REDACTED_IDENTIFIERS, "_MotDePasse,$Passwort");
    try {
      Redaction.addUserDefinedKeywords(Config.get());
      assertTrue(Redaction.isRedactedKeyword("mot-de-passe"));
      assertTrue(Redaction.isRedactedKeyword("Passwort"));
    } finally {
      System.clearProperty(REDACTED_IDENTIFIERS);
    }
  }
}
