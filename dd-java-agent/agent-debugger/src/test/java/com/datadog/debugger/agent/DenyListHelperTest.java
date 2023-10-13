package com.datadog.debugger.agent;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class DenyListHelperTest {

  @Test
  public void isDeniedClass() {
    DenyListHelper denyListHelper = new DenyListHelper(null);
    Assertions.assertTrue(denyListHelper.isDenied("java.lang.String"));
    Assertions.assertTrue(denyListHelper.isDenied("java.lang.Object"));
    Assertions.assertTrue(denyListHelper.isDenied("java.security.Security"));
    Assertions.assertTrue(denyListHelper.isDenied("javax.security.auth.AuthPermission"));
    Assertions.assertFalse(denyListHelper.isDenied("java.util.List"));
    Assertions.assertFalse(denyListHelper.isDenied("List"));
    Assertions.assertFalse(denyListHelper.isDenied(null));
    Assertions.assertFalse(denyListHelper.isDenied(""));
  }
}
