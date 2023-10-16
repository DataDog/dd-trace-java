package datadog.trace.bootstrap.debugger.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RedactionTest {

  @Test
  public void test() {
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
}
