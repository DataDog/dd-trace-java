package datadog.trace.bootstrap;

import datadog.trace.api.ConfigHelper;
import java.util.EnumSet;
import org.slf4j.Logger;

/** Tracks third-party libraries that may need special handling during agent startup. */
public enum Library {
  WILDFLY;

  /**
   * Best-effort detection of libraries potentially used by the application. This is called at boot
   * so we need to be very careful how many checks happen here. Some library use may not be visible
   * to the agent at this point.
   */
  public static EnumSet<Library> detectLibraries(final Logger log) {
    final EnumSet<Library> libraries = EnumSet.noneOf(Library.class);

    final String jbossHome = ConfigHelper.getEnvironmentVariable("JBOSS_HOME");
    if (jbossHome != null) {
      log.debug("Env - jboss: {}", jbossHome);
      libraries.add(WILDFLY);
    }

    if (!libraries.isEmpty()) {
      log.debug("Detected {}", libraries);
    }
    return libraries;
  }
}
