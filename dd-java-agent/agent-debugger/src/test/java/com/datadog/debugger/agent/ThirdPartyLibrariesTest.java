package com.datadog.debugger.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.api.Config;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ThirdPartyLibrariesTest {

  private final Config mockConfig = mock(Config.class);

  @BeforeEach
  void setUp() {
    when(mockConfig.getThirdPartyIncludes()).thenReturn(Collections.emptySet());
    when(mockConfig.getThirdPartyExcludes()).thenReturn(Collections.emptySet());
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
}
