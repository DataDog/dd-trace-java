package com.datadog.debugger.symbol;

import static org.junit.jupiter.api.Assertions.*;

import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import org.junit.jupiter.api.Test;

class JarScannerTest {
  @Test
  public void extractJarPathFromJar()
      throws ClassNotFoundException, URISyntaxException, MalformedURLException {
    final String CLASS_NAME = "com.datadog.debugger.symbol.SymbolExtraction01";
    URL jarFileUrl = getClass().getResource("/debugger-symbol.jar");
    URL jarUrl = new URL("jar:file:" + jarFileUrl.getFile() + "!/");
    URLClassLoader urlClassLoader = new URLClassLoader(new URL[] {jarUrl}, null);
    Class<?> testClass = urlClassLoader.loadClass(CLASS_NAME);
    assertEquals(jarFileUrl.getFile(), JarScanner.extractJarPath(testClass).toString());
    assertEquals(
        jarFileUrl.getFile(),
        JarScanner.extractJarPath(testClass.getProtectionDomain()).toString());
  }

  @Test
  public void extractJarPathFromFile() throws ClassNotFoundException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.symbol.SymbolExtraction01";
    URL jarFileUrl = getClass().getResource("/debugger-symbol.jar");
    URLClassLoader urlClassLoader = new URLClassLoader(new URL[] {jarFileUrl}, null);
    Class<?> testClass = urlClassLoader.loadClass(CLASS_NAME);
    assertEquals(jarFileUrl.getFile(), JarScanner.extractJarPath(testClass).toString());
  }
}
