package datadog.trace.plugin.csi.impl;

import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.plugin.csi.impl.HelperDependenciesFixtures.Base;
import datadog.trace.plugin.csi.impl.HelperDependenciesFixtures.Helper;
import datadog.trace.plugin.csi.impl.HelperDependenciesFixtures.Unrelated;
import datadog.trace.plugin.csi.impl.HelperDependenciesFixtures.Used;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

class HelperDependenciesTest {

  @Test
  void addsModuleClassesTheHelperDependsOn() throws Exception {
    List<String> helpers = expand(Type.getType(Helper.class));

    assertTrue(helpers.contains(name(Helper.class)), () -> "helpers: " + helpers);
    assertTrue(helpers.contains(name(Helper.class) + "$1"), () -> "helpers: " + helpers);
    assertTrue(helpers.contains(name(Base.class)), () -> "helpers: " + helpers);
    assertTrue(helpers.contains(name(Used.class)), () -> "helpers: " + helpers);
    assertFalse(helpers.contains(name(Unrelated.class)), () -> "helpers: " + helpers);
    assertFalse(helpers.contains(Object.class.getName()), () -> "helpers: " + helpers);
  }

  @Test
  void ordersSupertypesBeforeSubtypes() throws Exception {
    List<String> helpers = expand(Type.getType(Helper.class));

    int base = helpers.indexOf(name(Base.class));
    int anonymous = helpers.indexOf(name(Helper.class) + "$1");
    assertTrue(base >= 0 && base < anonymous, () -> "helpers: " + helpers);
  }

  @Test
  void keepsDeclaredHelpersOutsideTheModule() throws Exception {
    Type outside = Type.getObjectType("does/not/Exist");

    assertEquals(asList("does.not.Exist"), expand(outside));
  }

  private static List<String> expand(Type... helpers) throws Exception {
    return Arrays.stream(HelperDependencies.expand(classesFolder(), helpers))
        .map(Type::getClassName)
        .collect(Collectors.toList());
  }

  private static Path classesFolder() throws Exception {
    return Paths.get(
        HelperDependenciesTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
  }

  private static String name(Class<?> type) {
    return type.getName();
  }
}
