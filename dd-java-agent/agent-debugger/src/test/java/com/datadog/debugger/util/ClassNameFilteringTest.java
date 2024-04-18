package com.datadog.debugger.util;

import static com.datadog.debugger.agent.ThirdPartyLibraries.ALPHABET;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datadog.debugger.agent.ThirdPartyLibraries;
import datadog.trace.api.Config;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ClassNameFilteringTest {

  @Test
  public void testAllowAll() {
    ClassNameFiltering classNameFiltering = ClassNameFiltering.allowAll();
    assertFalse(classNameFiltering.isExcluded("java.FooBar"));
  }

  @Test
  public void testExcludeAll() {
    List<String> excludeAllPrefixes = new ArrayList<>();
    for (char c : ALPHABET) {
      excludeAllPrefixes.add(String.valueOf(c));
    }
    ClassNameFiltering classNameFiltering = new ClassNameFiltering(excludeAllPrefixes);
    assertTrue(classNameFiltering.isExcluded("asd.fgh.ijk.FooBar"));
  }

  @Test
  public void testExcludeSome() {
    List<String> excludeSomePrefixes = new ArrayList<>();
    excludeSomePrefixes.add("com.datadog.debugger");
    excludeSomePrefixes.add("org.junit");
    ClassNameFiltering classNameFiltering = new ClassNameFiltering(excludeSomePrefixes);
    assertTrue(classNameFiltering.isExcluded("com.datadog.debugger.FooBar"));
    assertTrue(classNameFiltering.isExcluded("org.junit.FooBar"));
    assertFalse(classNameFiltering.isExcluded("akka.Actor"));
    assertFalse(classNameFiltering.isExcluded("cats.Functor"));
  }

  @Test
  public void testIncludeOverridesExclude() {
    ClassNameFiltering classNameFiltering =
        new ClassNameFiltering(
            Collections.singletonList("com.datadog.debugger"),
            Collections.singletonList("com.datadog.debugger"));
    assertFalse(classNameFiltering.isExcluded("com.datadog.debugger.FooBar"));
  }

  @Test
  public void testIncludePrefixOverridesExclude() {
    ClassNameFiltering classNameFiltering =
        new ClassNameFiltering(
            Collections.singletonList("com.datadog.debugger"),
            Collections.singletonList("com.datadog"));
    assertFalse(classNameFiltering.isExcluded("com.datadog.debugger.FooBar"));
  }

  @Test
  public void testIncludeSomeExcludeSome() {
    ClassNameFiltering classNameFiltering =
        new ClassNameFiltering(
            Arrays.asList("com.datadog.debugger", "org.junit"),
            Collections.singletonList("com.datadog.debugger"));
    assertFalse(classNameFiltering.isExcluded("com.datadog.debugger.FooBar"));
    assertTrue(classNameFiltering.isExcluded("org.junit.FooBar"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "java.FooBar",
        "org.junit.Test",
        "org.junit.jupiter.api.Test",
        "akka.Actor",
        "cats.Functor",
        "org.junit.jupiter.api.Test",
        "org.junit.jupiter.api.Test",
        "org.datadog.jmxfetch.FooBar"
      })
  public void testExcludeDefaults(String input) {
    Config config = mock(Config.class);
    when(config.getThirdPartyExcludes()).thenReturn("");
    when(config.getThirdPartyIncludes()).thenReturn("");
    ClassNameFiltering classNameFiltering =
        new ClassNameFiltering(ThirdPartyLibraries.INSTANCE.getExcludes(config));
    assertTrue(classNameFiltering.isExcluded(input));
  }
}
