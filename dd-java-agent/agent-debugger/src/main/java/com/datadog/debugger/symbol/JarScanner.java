package com.datadog.debugger.symbol;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JarScanner {
  private static final Logger LOGGER = LoggerFactory.getLogger(JarScanner.class);
  private static final String JAR_FILE_PREFIX = "jar:file:";
  private static final String JAR_NESTED_PREFIX = "jar:nested:";
  private static final String FILE_PREFIX = "file:";
  // Spring prefixes:
  // https://docs.spring.io/spring-boot/docs/current/reference/html/executable-jar.html
  private static final String SPRING_CLASSES_PREFIX = "BOOT-INF/classes/";
  private static final String SPRING_DEPS_PREFIX = "BOOT-INF/lib/";

  public static Path extractJarPath(Class<?> clazz, SymDBReport symDBReport)
      throws URISyntaxException {
    return extractJarPath(clazz.getProtectionDomain(), symDBReport);
  }

  public static Path extractJarPath(ProtectionDomain protectionDomain, SymDBReport symDBReport) {
    if (protectionDomain == null) {
      return null;
    }
    CodeSource codeSource = protectionDomain.getCodeSource();
    if (codeSource == null) {
      return null;
    }
    URL location = codeSource.getLocation();
    if (location == null) {
      return null;
    }
    String locationStr = location.toString();
    LOGGER.debug("CodeSource Location={}", locationStr);
    if (locationStr.startsWith(JAR_FILE_PREFIX)) {
      int idx = locationStr.indexOf("!/");
      if (idx != -1) {
        return getPathFromPrefixedFileName(locationStr, JAR_FILE_PREFIX, idx);
      }
    } else if (locationStr.startsWith(JAR_NESTED_PREFIX)) {
      int idx = locationStr.indexOf("/!BOOT-INF/");
      if (idx != -1) {
        return getPathFromPrefixedFileName(locationStr, JAR_NESTED_PREFIX, idx);
      }
    } else if (locationStr.startsWith(FILE_PREFIX)) {
      return getPathFromPrefixedFileName(locationStr, FILE_PREFIX, locationStr.length());
    }
    symDBReport.addLocationError(locationStr);
    return null;
  }

  public static String trimPrefixes(String classFilePath) {
    if (classFilePath.startsWith(SPRING_CLASSES_PREFIX)) {
      return classFilePath.substring(SPRING_CLASSES_PREFIX.length());
    }
    return classFilePath;
  }

  private static Path getPathFromPrefixedFileName(String locationStr, String prefix, int endIdx) {
    String fileName = locationStr.substring(prefix.length(), endIdx);
    LOGGER.debug("jar filename={}", fileName);
    return Paths.get(fileName);
  }
}
