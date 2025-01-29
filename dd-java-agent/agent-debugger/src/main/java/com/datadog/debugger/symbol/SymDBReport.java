package com.datadog.debugger.symbol;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SymDBReport {
  private static final Logger LOGGER = LoggerFactory.getLogger(SymDBReport.class);

  private final Set<String> missingJars = new HashSet<>();
  private final Set<String> directoryJars = new HashSet<>();
  private final Map<String, String> ioExceptions = new HashMap<>();
  private final List<String> locationErrors = new ArrayList<>();

  public void addMissingJar(String jarPath) {
    missingJars.add(jarPath);
  }

  public void addDirectoryJar(String jarPath) {
    directoryJars.add(jarPath);
  }

  public void addIOException(String jarPath, IOException e) {
    ioExceptions.put(jarPath, e.toString());
  }

  public void addLocationError(String locationStr) {
    locationErrors.add(locationStr);
  }

  public void report() {
    String content =
        "== SymDB Report == Location errors:"
            + locationErrors
            + " Missing jars: "
            + missingJars
            + " Directory jars: "
            + directoryJars
            + " IOExceptions: "
            + ioExceptions;
    LOGGER.info(content);
  }
}
