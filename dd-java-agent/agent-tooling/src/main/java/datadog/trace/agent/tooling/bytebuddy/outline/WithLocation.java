package datadog.trace.agent.tooling.bytebuddy.outline;

import java.net.URL;

/** Provides details of where the resolved type was defined. */
public interface WithLocation {
  int getClassLoaderId();

  URL getClassFile();

  byte[] getBytecode();
}
