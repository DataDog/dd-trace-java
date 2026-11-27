package datadog.trace.util;

import static datadog.trace.util.JDK9ModuleAccess.addModuleReads;
import static datadog.trace.util.JDK9ModuleAccess.canRead;
import static datadog.trace.util.JDK9ModuleAccess.exportModuleToUnnamedModule;
import static datadog.trace.util.JDK9ModuleAccess.getUnnamedModule;
import static java.util.Collections.singleton;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.net.URLClassLoader;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class JDK9ModuleAccessTest {

  private static Instrumentation inst;

  @BeforeAll
  static void installAgent() {
    inst = ByteBuddyAgent.install();
  }

  @Test
  void getUnnamedModuleReturnsClassLoaderUnnamedModule() {
    ClassLoader cl = newClassLoader();

    assertSame(cl.getUnnamedModule(), getUnnamedModule(cl));
  }

  @Test
  void canReadReflectsModuleReadability() {
    Module unnamed = newClassLoader().getUnnamedModule();
    Module javaBase = Object.class.getModule();

    // unnamed modules read every module, java.base reads no unnamed module
    assertTrue(canRead(unnamed, javaBase));
    assertFalse(canRead(javaBase, unnamed));
  }

  @Test
  void addModuleReadsMakesModuleReadable() {
    Module javaSql = java.sql.Driver.class.getModule();
    Module unnamed = newClassLoader().getUnnamedModule();
    assertFalse(javaSql.canRead(unnamed));

    addModuleReads(inst, javaSql, singleton(unnamed));

    assertTrue(javaSql.canRead(unnamed));
  }

  @Test
  void exportModuleToUnnamedModuleExportsPackages() {
    ClassLoader cl = newClassLoader();
    Module javaBase = Object.class.getModule();
    assertFalse(javaBase.isExported("jdk.internal.misc", cl.getUnnamedModule()));

    exportModuleToUnnamedModule(inst, "java.base", new String[] {"jdk.internal.misc"}, cl);

    assertTrue(javaBase.isExported("jdk.internal.misc", cl.getUnnamedModule()));
  }

  @Test
  void exportModuleToUnnamedModuleIgnoresUnknownModule() {
    ClassLoader cl = newClassLoader();

    assertDoesNotThrow(
        () -> exportModuleToUnnamedModule(inst, "does.not.exist", new String[] {"some.pkg"}, cl));
  }

  /** A fresh class loader, so module changes don't leak to other tests. */
  private static ClassLoader newClassLoader() {
    return new URLClassLoader(new URL[0], null);
  }
}
