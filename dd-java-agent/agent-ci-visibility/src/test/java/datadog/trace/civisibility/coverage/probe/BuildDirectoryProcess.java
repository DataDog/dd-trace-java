package datadog.trace.civisibility.coverage.probe;

import java.io.File;
import java.nio.file.Paths;

/**
 * Stands in for the JVM of a build in {@link ProbeClassCacheTest}: creates a build directory in the
 * directory named by the first argument and prints its path. With a second argument, {@code wait},
 * it then runs until its input ends or it is killed.
 */
public final class BuildDirectoryProcess {

  public static void main(String[] args) throws Exception {
    File directory = ProbeClassCache.createBuildDirectory(Paths.get(args[0]));
    System.out.println(directory);
    System.out.flush();
    if (args.length > 1 && args[1].equals("wait")) {
      while (System.in.read() >= 0) {
        // runs until its input ends
      }
    }
  }
}
