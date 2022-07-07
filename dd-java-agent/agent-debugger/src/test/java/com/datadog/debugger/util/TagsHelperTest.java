package com.datadog.debugger.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

public class TagsHelperTest {

  @Test
  public void validTagDoesNotChange() {
    String keyValue = "key:value";
    String validChars = "abc..xyz1234567890-_./:";
    assertEquals(keyValue, TagsHelper.sanitize(keyValue));
    assertEquals(validChars, TagsHelper.sanitize(validChars));
  }

  @Test
  public void nullIsSupported() {
    assertEquals(null, TagsHelper.sanitize(null));
  }

  @Test
  public void upperCase() {
    assertEquals("key:value", TagsHelper.sanitize("key:VALUE"));
  }

  @Test
  public void trimSpaces() {
    assertEquals("service-name", TagsHelper.sanitize("    service-name  "));
    assertEquals("service_name", TagsHelper.sanitize("    service name  "));
  }

  @Test
  public void invalidCharsConvertedToUnderscore() {
    assertEquals("my_email.com", TagsHelper.sanitize("my@email.com"));
    assertEquals("smile_and__", TagsHelper.sanitize("smile and \u1234"));
  }

  @Test
  public void tagTrimmedToMaxLength() {
    StringBuilder tag = new StringBuilder();
    for (int i = 0; i < 400; i++) {
      tag.append("a");
    }
    assertEquals(200, TagsHelper.sanitize(tag.toString()).length());
    assertEquals(200, TagsHelper.sanitize(tag.toString()).getBytes(StandardCharsets.UTF_8).length);
  }

  @Test
  public void tagTrimmedToMaxLengthWorkWithUnicode() {
    StringBuilder tag = new StringBuilder();
    for (int i = 0; i < 400; i++) {
      tag.append("\u1234");
    }
    assertEquals(200, TagsHelper.sanitize(tag.toString()).length());
    assertEquals(200, TagsHelper.sanitize(tag.toString()).getBytes(StandardCharsets.UTF_8).length);
  }
}
