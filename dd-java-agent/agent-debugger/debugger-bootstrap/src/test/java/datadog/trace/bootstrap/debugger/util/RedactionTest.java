package datadog.trace.bootstrap.debugger.util;

import static org.junit.jupiter.api.Assertions.*;

import datadog.trace.api.Config;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
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
    Config config = Config.get();
    setFieldInConfig(config, "dynamicInstrumentationRedactedIdentifiers", "_MotDePasse,$Passwort");
    try {
      Redaction.addUserDefinedKeywords(config);
      assertTrue(Redaction.isRedactedKeyword("mot-de-passe"));
      assertTrue(Redaction.isRedactedKeyword("Passwort"));
    } finally {
      Redaction.resetUserDefinedKeywords();
    }
  }

  @Test
  public void userDefinedTypes() {
    Config config = Config.get();
    setFieldInConfig(
        config, "dynamicInstrumentationRedactedTypes", "java.security.Security,javax.security.*");
    try {
      Redaction.addUserDefinedTypes(Config.get());
      assertTrue(Redaction.isRedactedType("java.security.Security"));
      assertTrue(Redaction.isRedactedType("javax.security.SecurityContext"));
    } finally {
      Redaction.clearUserDefinedTypes();
    }
  }

  @Test
  public void exclusions() {
    Config config = Config.get();
    setFieldInConfig(
        config,
        "dynamicInstrumentationRedactionExcludedIdentifiers",
        new HashSet<>(Arrays.asList("password", "_2FA")));
    Redaction.initKeywords();
    try {
      assertFalse(Redaction.isRedactedKeyword("password"));
      assertFalse(Redaction.isRedactedKeyword("_2fa"));
    } finally {
      setFieldInConfig(
          config, "dynamicInstrumentationRedactionExcludedIdentifiers", Collections.emptySet());
      Redaction.initKeywords();
    }
  }

  private static void setFieldInConfig(Config config, String fieldName, Object value) {
    try {
      Field field = config.getClass().getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(config, value);
    } catch (Throwable e) {
      e.printStackTrace();
    }
  }
}
