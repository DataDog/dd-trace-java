package com.datadog.debugger.symbol;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

class JarScannerTest {
  @Test
  public void extractJarPathFromJar()
      throws ClassNotFoundException, URISyntaxException, MalformedURLException {
    final String CLASS_NAME = "com.datadog.debugger.symbol.SymbolExtraction01";
    URL jarFileUrl = getClass().getResource("/debugger-symbol.jar");
    URL jarUrl = new URL("jar:file:" + jarFileUrl.getFile() + "!/");
    URLClassLoader urlClassLoader = new URLClassLoader(new URL[] {jarUrl}, null);
    Class<?> testClass = urlClassLoader.loadClass(CLASS_NAME);
    assertEquals(
        jarFileUrl.getFile(), JarScanner.extractJarPath(testClass, SymDBReport.NO_OP).toString());
    assertEquals(
        jarFileUrl.getFile(),
        JarScanner.extractJarPath(testClass.getProtectionDomain(), null).toString());
  }

  @Test
  public void extractJarPathFromFile() throws ClassNotFoundException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.symbol.SymbolExtraction01";
    URL jarFileUrl = getClass().getResource("/debugger-symbol.jar");
    URLClassLoader urlClassLoader = new URLClassLoader(new URL[] {jarFileUrl}, null);
    Class<?> testClass = urlClassLoader.loadClass(CLASS_NAME);
    assertEquals(
        jarFileUrl.getFile(), JarScanner.extractJarPath(testClass, SymDBReport.NO_OP).toString());
  }

  @Test
  public void extractJarPathFromNestedJar() throws URISyntaxException {
    URL jarFileUrl = getClass().getResource("/debugger-symbol.jar");
    URL mockLocation = mock(URL.class);
    when(mockLocation.toString())
        .thenReturn("jar:nested:" + jarFileUrl.getFile() + "/!BOOT-INF/classes/!");
    CodeSource codeSource = new CodeSource(mockLocation, (Certificate[]) null);
    ProtectionDomain protectionDomain = new ProtectionDomain(codeSource, null);
    assertEquals(
        jarFileUrl.getFile(), JarScanner.extractJarPath(protectionDomain, null).toString());
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  public void extractJarPathFromFileOnWindows() throws URISyntaxException {
    URL mockLocation = mock(URL.class);
    when(mockLocation.toString()).thenReturn("file:/C:/apps/server/classes/");
    CodeSource codeSource = new CodeSource(mockLocation, (Certificate[]) null);
    ProtectionDomain protectionDomain = new ProtectionDomain(codeSource, null);
    Path result = JarScanner.extractJarPath(protectionDomain, SymDBReport.NO_OP);
    assertNotNull(result);
    assertTrue(result.toString().contains("server"));
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  public void extractJarPathFromJarOnWindows() throws URISyntaxException {
    URL mockLocation = mock(URL.class);
    when(mockLocation.toString()).thenReturn("jar:file:/C:/libs/app.jar!/com/example/");
    CodeSource codeSource = new CodeSource(mockLocation, (Certificate[]) null);
    ProtectionDomain protectionDomain = new ProtectionDomain(codeSource, null);
    Path result = JarScanner.extractJarPath(protectionDomain, SymDBReport.NO_OP);
    assertNotNull(result);
    assertTrue(result.toString().contains("app.jar"));
  }
}
