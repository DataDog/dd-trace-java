package com.datadog.debugger.origin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.Config;
import datadog.trace.api.InstrumenterConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;

public class CodeOriginConfigTest {

  @EnabledForJreRange(min = JRE.JAVA_25)
  @Test
  public void defaultConfigJDK25() {
    assertTrue(Config.get().isDebuggerCodeOriginEnabled());
    assertTrue(InstrumenterConfig.get().isCodeOriginEnabled());
  }

  @EnabledOnJre(JRE.JAVA_21)
  @Test
  public void defaultConfigJDK21() {
    assertFalse(Config.get().isDebuggerCodeOriginEnabled());
    assertFalse(InstrumenterConfig.get().isCodeOriginEnabled());
  }

  @EnabledOnJre(JRE.JAVA_17)
  @Test
  public void defaultConfigJDK17() {
    assertFalse(Config.get().isDebuggerCodeOriginEnabled());
    assertFalse(InstrumenterConfig.get().isCodeOriginEnabled());
  }

  @EnabledOnJre(JRE.JAVA_11)
  @Test
  public void defaultConfigJDK11() {
    assertFalse(Config.get().isDebuggerCodeOriginEnabled());
    assertFalse(InstrumenterConfig.get().isCodeOriginEnabled());
  }

  @EnabledOnJre(JRE.JAVA_8)
  @Test
  public void defaultConfigJDK8() {
    assertFalse(Config.get().isDebuggerCodeOriginEnabled());
    assertFalse(InstrumenterConfig.get().isCodeOriginEnabled());
  }
}
