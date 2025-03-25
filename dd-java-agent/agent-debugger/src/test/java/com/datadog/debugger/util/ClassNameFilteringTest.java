package com.datadog.debugger.util;

import static com.datadog.debugger.agent.ThirdPartyLibraries.ALPHABET;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.api.Config;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
    Set<String> excludeAllPrefixes = new HashSet<>();
    for (char c : ALPHABET) {
      excludeAllPrefixes.add(String.valueOf(c));
    }
    ClassNameFiltering classNameFiltering = new ClassNameFiltering(excludeAllPrefixes);
    assertTrue(classNameFiltering.isExcluded("asd.fgh.ijk.FooBar"));
  }

  @Test
  public void testExcludeSome() {
    Set<String> excludeSomePrefixes = new HashSet<>();
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
            Collections.singleton("com.datadog.debugger"),
            Collections.singleton("com.datadog.debugger"),
            Collections.emptySet());
    assertFalse(classNameFiltering.isExcluded("com.datadog.debugger.FooBar"));
  }

  @Test
  public void testIncludePrefixOverridesExclude() {
    ClassNameFiltering classNameFiltering =
        new ClassNameFiltering(
            Collections.singleton("com.datadog.debugger"),
            Collections.singleton("com.datadog"),
            Collections.emptySet());
    assertFalse(classNameFiltering.isExcluded("com.datadog.debugger.FooBar"));
  }

  @Test
  public void testIncludeSomeExcludeSome() {
    ClassNameFiltering classNameFiltering =
        new ClassNameFiltering(
            Stream.of("com.datadog.debugger", "org.junit").collect(Collectors.toSet()),
            Collections.singleton("com.datadog.debugger"),
            Collections.emptySet());
    assertFalse(classNameFiltering.isExcluded("com.datadog.debugger.FooBar"));
    assertTrue(classNameFiltering.isExcluded("org.junit.FooBar"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "java.FooBar",
        "org.junit.Test",
        "akka.Actor",
        "cats.Functor",
        "org.junit.jupiter.api.Test",
        "org.datadog.jmxfetch.FooBar"
      })
  public void testExcludeDefaults(String input) {
    Config config = mock(Config.class);
    when(config.getThirdPartyExcludes()).thenReturn(Collections.emptySet());
    when(config.getThirdPartyIncludes()).thenReturn(Collections.emptySet());
    when(config.getThirdPartyShadingIdentifiers()).thenReturn(Collections.emptySet());
    ClassNameFiltering classNameFiltering = new ClassNameFiltering(config);
    assertTrue(classNameFiltering.isExcluded(input));
  }

  @Test
  public void testShaded() {
    Config config = mock(Config.class);
    when(config.getThirdPartyExcludes()).thenReturn(Collections.emptySet());
    when(config.getThirdPartyIncludes())
        .thenReturn(new HashSet<>(Arrays.asList("com.google", "org.junit")));
    when(config.getThirdPartyShadingIdentifiers()).thenReturn(Collections.emptySet());
    ClassNameFiltering classNameFiltering = new ClassNameFiltering(config);
    assertTrue(classNameFiltering.isExcluded("com.google.FooBar"));
    assertTrue(classNameFiltering.isExcluded("shaded.com.google.FooBar"));
    assertFalse(classNameFiltering.isExcluded("com.example.shaded.com.example.FooBar"));
    assertTrue(classNameFiltering.isExcluded("com.example.shaded.com.google.FooBar"));
  }

  @Test
  void lambdaProxyClasses() {
    // jdk8:  at
    // datadog.smoketest.debugger.ServerDebuggerTestApplication$$Lambda$231/1770027171.apply(<Unknown>:1000008)
    // jdk11: at
    // datadog.smoketest.debugger.ServerDebuggerTestApplication$$Lambda$262/0x0000000800467040.apply(Unknown Source)
    // jdk17:	at
    // datadog.smoketest.debugger.ServerDebuggerTestApplication$$Lambda$303/0x00000008013dd1f8.apply(Unknown Source)
    // jdk21: at
    // datadog.smoketest.debugger.ServerDebuggerTestApplication$$Lambda/0x000000b801392c58.apply(Unknown Source)
    assertTrue(
        ClassNameFiltering.isLambdaProxyClass(
            "datadog.smoketest.debugger.ServerDebuggerTestApplication$$Lambda$231/1770027171"));
    assertTrue(
        ClassNameFiltering.isLambdaProxyClass(
            "datadog.smoketest.debugger.ServerDebuggerTestApplication$$Lambda$262/0x0000000800467040"));
    assertTrue(
        ClassNameFiltering.isLambdaProxyClass(
            "at datadog.smoketest.debugger.ServerDebuggerTestApplication$$Lambda/0x000000b801392c58"));
  }
}
