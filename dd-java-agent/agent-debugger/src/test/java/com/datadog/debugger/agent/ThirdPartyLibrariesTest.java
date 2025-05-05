package com.datadog.debugger.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.api.Config;
import java.util.Collections;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ThirdPartyLibrariesTest {

  private final Config mockConfig = mock(Config.class);

  @BeforeEach
  void setUp() {
    when(mockConfig.getThirdPartyIncludes()).thenReturn(Collections.emptySet());
    when(mockConfig.getThirdPartyExcludes()).thenReturn(Collections.emptySet());
    when(mockConfig.getThirdPartyShadingIdentifiers()).thenReturn(Collections.emptySet());
  }

  @Test
  void testGetExcludesContainsDefaultExclude() {
    assertTrue(ThirdPartyLibraries.INSTANCE.getThirdPartyLibraries(mockConfig).contains("java."));
  }

  @Test
  void testGetExcludesWithExplicitExclude() {
    when(mockConfig.getThirdPartyIncludes())
        .thenReturn(Collections.singleton("com.datadog.debugger"));
    assertTrue(
        ThirdPartyLibraries.INSTANCE
            .getThirdPartyLibraries(mockConfig)
            .contains("com.datadog.debugger"));
  }

  @Test
  void testGetExcludesWithExplicitExcludeAndExplicitInclude() {
    when(mockConfig.getThirdPartyIncludes())
        .thenReturn(Collections.singleton("com.datadog.debugger"));
    when(mockConfig.getThirdPartyExcludes())
        .thenReturn(Collections.singleton("com.datadog.debugger"));
    assertTrue(
        ThirdPartyLibraries.INSTANCE
            .getThirdPartyLibraries(mockConfig)
            .contains("com.datadog.debugger"));
    assertTrue(
        ThirdPartyLibraries.INSTANCE
            .getThirdPartyExcludes(mockConfig)
            .contains("com.datadog.debugger"));
  }

  @Test
  void testGetExcludesWithoutExplicitConfig() {
    assertNotNull(ThirdPartyLibraries.INSTANCE.getThirdPartyLibraries(mockConfig));
    assertFalse(ThirdPartyLibraries.INSTANCE.getThirdPartyLibraries(mockConfig).isEmpty());
  }

  @Test
  void testGetExcludesWithIncludeOverridingDefaultExclude() {
    when(mockConfig.getThirdPartyExcludes()).thenReturn(Collections.singleton("java."));
    assertTrue(ThirdPartyLibraries.INSTANCE.getThirdPartyLibraries(mockConfig).contains("java."));
    assertTrue(ThirdPartyLibraries.INSTANCE.getThirdPartyExcludes(mockConfig).contains("java."));
  }

  @Test
  void testGetExcludeAll() {
    Set<String> excludeAll = ThirdPartyLibraries.INSTANCE.getThirdPartyLibraries(null);
    for (char c : ThirdPartyLibraries.ALPHABET) assertTrue(excludeAll.contains(String.valueOf(c)));
  }

  @Test
  void testEmptyStrings() {
    int expectedIncludeDefaultSize =
        ThirdPartyLibraries.INSTANCE.getThirdPartyLibraries(mockConfig).size();
    int expectedShadingDefaultSize =
        ThirdPartyLibraries.INSTANCE.getShadingIdentifiers(mockConfig).size();
    when(mockConfig.getThirdPartyIncludes()).thenReturn(Collections.singleton(""));
    when(mockConfig.getThirdPartyExcludes()).thenReturn(Collections.singleton(""));
    when(mockConfig.getThirdPartyShadingIdentifiers()).thenReturn(Collections.singleton(""));
    assertEquals(
        expectedIncludeDefaultSize,
        ThirdPartyLibraries.INSTANCE.getThirdPartyLibraries(mockConfig).size());
    assertTrue(ThirdPartyLibraries.INSTANCE.getThirdPartyExcludes(mockConfig).isEmpty());
    assertEquals(
        expectedShadingDefaultSize,
        ThirdPartyLibraries.INSTANCE.getShadingIdentifiers(mockConfig).size());
  }
}
