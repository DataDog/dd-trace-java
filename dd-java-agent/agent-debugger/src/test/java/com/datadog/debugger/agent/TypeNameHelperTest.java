package com.datadog.debugger.agent;

import java.util.Map;
import org.junit.Assert;
import org.junit.jupiter.api.Test;

public class TypeNameHelperTest {

  @Test
  public void extractSimpleName() {
    Assert.assertEquals("String", TypeNameHelper.extractSimpleName(String.class));
    Assert.assertEquals("String", TypeNameHelper.extractSimpleNameFromName(String.class.getName()));
    Assert.assertEquals(
        "Entry", TypeNameHelper.extractSimpleNameFromName(Map.Entry.class.getName()));
  }
}
