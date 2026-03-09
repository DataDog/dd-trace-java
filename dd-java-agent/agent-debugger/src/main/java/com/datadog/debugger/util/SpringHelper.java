package com.datadog.debugger.util;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SpringHelper {
  private static final Logger LOGGER = LoggerFactory.getLogger(SpringHelper.class);

  public static boolean isSpringUsingOnlyMethodParameters(Instrumentation inst) {
    try {
      return isSpringUsingOnlyMethodParametersSpringVersion(inst);
    } catch (Exception e) {
      LOGGER.debug("isSpringUsingOnlyMethodParameters failed for SpringVersion", e);
      // fallback to lookup for specific class
      return isSpringUsingOnlyMethodParametersSpecificClass(inst);
    }
  }

  private static boolean isSpringUsingOnlyMethodParametersSpringVersion(Instrumentation inst) {
    try {
      // scan for getting an already loaded class and get the classloader
      ClassLoader springClassLoader = null;
      for (Class<?> clazz : inst.getAllLoadedClasses()) {
        if (clazz.getName().startsWith("org.springframework.core")) {
          springClassLoader = clazz.getClassLoader();
        }
      }
      if (springClassLoader == null) {
        throw new IllegalStateException("Cannot find Spring classloader");
      }
      Class<?> springVersionClass =
          Class.forName("org.springframework.core.SpringVersion", true, springClassLoader);
      Method m = springVersionClass.getDeclaredMethod("getVersion");
      String version = (String) m.invoke(null);
      ParsedSpringVersion springVersion = new ParsedSpringVersion(version);
      // if Spring version is 6.1+ only using MethodParameters
      return springVersion.major > 6 || (springVersion.major == 6 && springVersion.minor >= 1);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static boolean isSpringUsingOnlyMethodParametersSpecificClass(Instrumentation inst) {
    for (Class<?> clazz : inst.getAllLoadedClasses()) {
      if ("org.springframework.web.client.RestClient".equals(clazz.getName())) {
        // If this class (coming from Spring web since version 6.1) is found loaded it means Spring
        // supports only getting parameter names from the MethodParameter attribute
        return true;
      }
    }
    // class not found, probably no Spring
    return false;
  }

  private static class ParsedSpringVersion {
    private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)");

    final int major;
    final int minor;
    final int patch;

    public ParsedSpringVersion(String strVersion) {
      Matcher matcher = VERSION_PATTERN.matcher(strVersion);
      if (matcher.find()) {
        major = Integer.parseInt(matcher.group(1));
        minor = Integer.parseInt(matcher.group(2));
        patch = Integer.parseInt(matcher.group(3));
      } else {
        throw new IllegalArgumentException("Cannot parse SpringVersion: " + strVersion);
      }
    }
  }
}
