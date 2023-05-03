package com.datadog.debugger.agent;

import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TypeNameHelperTest {

  @Test
  public void extractSimpleName() {
    Assertions.assertEquals("String", TypeNameHelper.extractSimpleName(String.class));
    Assertions.assertEquals(
        "String", TypeNameHelper.extractSimpleNameFromName(String.class.getTypeName()));
    Assertions.assertEquals(
        "Entry", TypeNameHelper.extractSimpleNameFromName(Map.Entry.class.getTypeName()));
  }
}
