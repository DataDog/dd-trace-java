package com.datadog.debugger.agent;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Assert;
import org.junit.jupiter.api.Test;

public class AllowListHelperTest {

  @Test
  public void allowAll() {
    AllowListHelper allowListHelper = new AllowListHelper(null);
    Assert.assertTrue(allowListHelper.isAllowed("foo"));
    allowListHelper = create(Collections.emptyList(), Collections.emptyList());
    Assert.assertTrue(allowListHelper.isAllowed("foo"));
  }

  @Test
  public void simple() {
    AllowListHelper allowListHelper =
        create(Arrays.asList("com.datadog"), Arrays.asList("java.util.HashMap"));
    Assert.assertTrue(allowListHelper.isAllowed(AllowListHelper.class.getName()));
    Assert.assertTrue(allowListHelper.isAllowed("java.util.HashMap"));
    Assert.assertFalse(allowListHelper.isAllowed("org.junit.jupiter.api.Test"));
    Assert.assertFalse(allowListHelper.isAllowed("java.util.ArrayList"));
  }

  private static AllowListHelper create(List<String> packagePrefixes, List<String> classes) {
    return new AllowListHelper(new Configuration.FilterList(packagePrefixes, classes));
  }
}
