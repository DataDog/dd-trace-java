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

public class BasicSymDBReport implements SymDBReport {
  private static final Logger LOGGER = LoggerFactory.getLogger(BasicSymDBReport.class);

  private final Set<String> missingJars = new HashSet<>();
  private final Map<String, String> ioExceptions = new HashMap<>();
  private final List<String> locationErrors = new ArrayList<>();
  private final Map<String, Integer> classCountByJar = new HashMap<>();
  private final List<String> scannedJars = new ArrayList<>();

  public void addMissingJar(String jarPath) {
    missingJars.add(jarPath);
  }

  public void addIOException(String jarPath, IOException e) {
    ioExceptions.put(jarPath, e.toString());
  }

  public void addLocationError(String locationStr) {
    locationErrors.add(locationStr);
  }

  public void incClassCount(String jarPath) {
    classCountByJar.compute(jarPath, (k, v) -> v == null ? 1 : v + 1);
  }

  public void addScannedJar(String jarPath) {
    scannedJars.add(jarPath);
  }

  public void report() {
    int totalClasses = classCountByJar.values().stream().mapToInt(Integer::intValue).sum();
    String content =
        String.format(
            "SymDB Report: Scanned jar count=%d, Total class count=%d, class count by jar: %s, Scanned jars: %s, Location errors: %s Missing jars: %s IOExceptions: %s",
            scannedJars.size(),
            totalClasses,
            classCountByJar,
            scannedJars,
            locationErrors,
            missingJars,
            ioExceptions);
    LOGGER.info(content);
  }
}
