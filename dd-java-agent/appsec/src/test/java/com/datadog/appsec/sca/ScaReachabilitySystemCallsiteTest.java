package com.datadog.appsec.sca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link ScaReachabilitySystem#findCallsite(String, StackTraceElement[])}. */
class ScaReachabilitySystemCallsiteTest {

  private static final String VULNERABLE_CLASS = "org.yaml.snakeyaml.Yaml";

  @Test
  void findCallsite_returnsNullWhenVulnerableClassIsNotInStack() {
    StackTraceElement[] stack = {
      frame("sca.test.TestController", "doSomething"),
    };

    assertNull(
        ScaReachabilitySystem.findCallsite("com.example.ClassNotOnStack", stack),
        "Should return null when vulnerable class is not on the stack");
  }

  @Test
  void findCallsite_returnsDirectCallerWhenNoIntermediateLibrary() {
    StackTraceElement[] stack = {
      frame(VULNERABLE_CLASS, "load"), frame("sca.test.TestController", "yamlHitDirect"),
    };

    StackTraceElement result = ScaReachabilitySystem.findCallsite(VULNERABLE_CLASS, stack);

    assertEquals("sca.test.TestController", result.getClassName());
    assertEquals("yamlHitDirect", result.getMethodName());
  }

  @Test
  void findCallsite_skipsRepeatedVulnerableFrames() {
    StackTraceElement[] stack = {
      frame(VULNERABLE_CLASS, "load"),
      frame(VULNERABLE_CLASS, "loadAll"),
      frame("sca.test.TestController", "yamlHitRecursive"),
    };

    StackTraceElement result = ScaReachabilitySystem.findCallsite(VULNERABLE_CLASS, stack);

    assertEquals("sca.test.TestController", result.getClassName());
    assertEquals("yamlHitRecursive", result.getMethodName());
  }

  @Test
  void findCallsite_skipsIntermediateLibraryFrameAndReturnsClientCode() {
    // com.google.* is excluded by the SCA trie (value >= 1)
    StackTraceElement[] stack = {
      frame(VULNERABLE_CLASS, "load"),
      frame("com.google.yaml.YamlWrapper", "load"),
      frame("sca.test.TestController", "yamlHitTransitive"),
    };

    StackTraceElement result = ScaReachabilitySystem.findCallsite(VULNERABLE_CLASS, stack);

    assertEquals(
        "sca.test.TestController",
        result.getClassName(),
        "Should skip intermediate library frame and return application code");
    assertEquals("yamlHitTransitive", result.getMethodName());
  }

  @Test
  void findCallsite_skipsMultipleIntermediateLibraryFrames() {
    StackTraceElement[] stack = {
      frame(VULNERABLE_CLASS, "load"),
      frame("com.google.yaml.YamlWrapper", "load"),
      frame("org.springframework.beans.factory.xml.XmlBeanFactory", "init"),
      frame("sca.test.TestController", "yamlHitDeep"),
    };

    StackTraceElement result = ScaReachabilitySystem.findCallsite(VULNERABLE_CLASS, stack);

    assertEquals("sca.test.TestController", result.getClassName());
    assertEquals("yamlHitDeep", result.getMethodName());
  }

  @Test
  void findCallsite_returnsNullWhenOnlyLibraryFramesFollowVulnerableClass() {
    StackTraceElement[] stack = {
      frame(VULNERABLE_CLASS, "load"),
      frame("com.google.yaml.YamlWrapper", "load"),
      frame("org.springframework.beans.factory.BeanFactory", "getBean"),
    };

    assertNull(
        ScaReachabilitySystem.findCallsite(VULNERABLE_CLASS, stack),
        "Should return null and trigger fallback when no application frame is found");
  }

  private static StackTraceElement frame(String className, String methodName) {
    return new StackTraceElement(className, methodName, null, -1);
  }
}
