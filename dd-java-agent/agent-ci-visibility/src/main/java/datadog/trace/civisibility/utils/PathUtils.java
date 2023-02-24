package datadog.trace.civisibility.utils;

import de.thetaphi.forbiddenapis.SuppressForbidden;

@SuppressForbidden
public class PathUtils {
  public static String expandTilde(final String path) {
    if (path == null || !path.startsWith("~")) {
      return path;
    }

    if (!path.equals("~") && !path.startsWith("~/")) {
      // Home dir expansion is not supported for other user.
      // Returning path without modifications.
      return path;
    }

    return path.replaceFirst("^~", System.getProperty("user.home"));
  }
}
