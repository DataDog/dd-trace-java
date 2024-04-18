package com.datadog.debugger.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.api.Config;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ThirdPartyLibrariesTest {

  private final Config mockConfig = mock(Config.class);

  @BeforeEach
  void setUp() {
    when(mockConfig.getThirdPartyIncludes()).thenReturn("");
    when(mockConfig.getThirdPartyExcludes()).thenReturn("");
  }

  @Test
  void testGetExcludesContainsDefaultExclude() {
    assertTrue(ThirdPartyLibraries.INSTANCE.getExcludes(mockConfig).contains("java."));
  }

  @Test
  void testGetExcludesWithExplicitExclude() {
    when(mockConfig.getThirdPartyExcludes()).thenReturn("com.datadog.debugger");
    assertTrue(
        ThirdPartyLibraries.INSTANCE.getExcludes(mockConfig).contains("com.datadog.debugger"));
  }

  @Test
  void testGetExcludesWithExplicitExcludeAndExplicitInclude() {
    when(mockConfig.getThirdPartyExcludes()).thenReturn("com.datadog.debugger");
    when(mockConfig.getThirdPartyIncludes()).thenReturn("com.datadog.debugger");
    assertTrue(
        ThirdPartyLibraries.INSTANCE.getExcludes(mockConfig).contains("com.datadog.debugger"));
    assertTrue(
        ThirdPartyLibraries.INSTANCE.getIncludes(mockConfig).contains("com.datadog.debugger"));
  }

  @Test
  void testGetExcludesWithoutExplicitConfig() {
    assertNotNull(ThirdPartyLibraries.INSTANCE.getExcludes(mockConfig));
    assertFalse(ThirdPartyLibraries.INSTANCE.getExcludes(mockConfig).isEmpty());
  }

  @Test
  void testGetExcludesWithIncludeOverridingDefaultExclude() {
    when(mockConfig.getThirdPartyIncludes()).thenReturn("java.");
    assertTrue(ThirdPartyLibraries.INSTANCE.getExcludes(mockConfig).contains("java."));
    assertTrue(ThirdPartyLibraries.INSTANCE.getIncludes(mockConfig).contains("java."));
  }
}
