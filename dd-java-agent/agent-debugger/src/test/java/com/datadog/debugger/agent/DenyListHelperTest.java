package com.datadog.debugger.agent;

import org.junit.Assert;
import org.junit.jupiter.api.Test;

public class DenyListHelperTest {

  @Test
  public void isDeniedClass() {
    DenyListHelper denyListHelper = new DenyListHelper(null);
    Assert.assertTrue(denyListHelper.isDenied("java.lang.String"));
    Assert.assertTrue(denyListHelper.isDenied("java.lang.Object"));
    Assert.assertTrue(denyListHelper.isDenied("java.security.Security"));
    Assert.assertTrue(denyListHelper.isDenied("javax.security.auth.AuthPermission"));
    Assert.assertFalse(denyListHelper.isDenied("java.util.List"));
    Assert.assertFalse(denyListHelper.isDenied("List"));
    Assert.assertFalse(denyListHelper.isDenied(null));
    Assert.assertFalse(denyListHelper.isDenied(""));
  }
}
