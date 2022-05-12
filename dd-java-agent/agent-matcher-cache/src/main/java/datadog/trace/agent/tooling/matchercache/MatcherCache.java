package datadog.trace.agent.tooling.matchercache;

import static datadog.trace.agent.tooling.matchercache.util.BinarySerializers.readInt;
import static datadog.trace.agent.tooling.matchercache.util.BinarySerializers.readString;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

public final class MatcherCache {

  public static class UnexpectedDataFormatVersion extends RuntimeException {
    public UnexpectedDataFormatVersion(
        int expectedMatcherCacheFormatVersion, int matcherCacheFileFormatVersion) {
      super(
          "Unknown Matcher Cache data format version. Expected: "
              + expectedMatcherCacheFormatVersion
              + " but read: "
              + matcherCacheFileFormatVersion);
    }
  }

  public static class IncompatibleJavaVersionData extends RuntimeException {
    public IncompatibleJavaVersionData(int expectedJavaMajorVersion, int javaMajorVersionInFile) {
      super(
          "Matcher Cache data file was built for Java "
              + javaMajorVersionInFile
              + " that is not compatible with Java "
              + expectedJavaMajorVersion);
    }
  }

  public static class IncompatibleTracerVersion extends RuntimeException {
    public IncompatibleTracerVersion(String tracerVersion, String tracerVersionInFile) {
      super(
          "Matcher Cache data file was built with Java Tracer "
              + tracerVersionInFile
              + " that is not compatible with current "
              + tracerVersion);
    }
  }

  public enum Result {
    TRANSFORM,
    SKIP,
    UNKNOWN,
  }

  public static MatcherCache deserialize(InputStream is, int javaMajorVersion, String tracerVersion)
      throws IOException {
    int matcherCacheFileFormatVersion = readInt(is);
    int expectedMatcherCacheFormatVersion = MatcherCacheBuilder.MATCHER_CACHE_FILE_FORMAT_VERSION;
    if (matcherCacheFileFormatVersion != expectedMatcherCacheFormatVersion) {
      throw new UnexpectedDataFormatVersion(
          expectedMatcherCacheFormatVersion, matcherCacheFileFormatVersion);
    }
    int javaMajorVersionInFile = readInt(is);
    if (javaMajorVersionInFile != javaMajorVersion) {
      throw new IncompatibleJavaVersionData(javaMajorVersion, javaMajorVersionInFile);
    }
    String tracerVersionInFile = readString(is);
    if (!tracerVersionInFile.equals(tracerVersion)) {
      throw new IncompatibleTracerVersion(tracerVersion, tracerVersionInFile);
    }
    int numberOfPackages = readInt(is);
    assert numberOfPackages >= 0;
    String[] packagesOrdered = new String[numberOfPackages];
    int[][] transformedClassHashes = new int[numberOfPackages][];
    String prevPackageName = "";
    for (int i = 0; i < numberOfPackages; i++) {
      String packageName = readString(is);
      if (packageName.compareTo(prevPackageName) < 0) {
        throw new IllegalStateException(
            "Unordered packages detected: '"
                + prevPackageName
                + "' goes before '"
                + packageName
                + "'");
      }
      prevPackageName = packageName;
      packagesOrdered[i] = packageName;
      transformedClassHashes[i] = readData(is);
    }
    return new MatcherCache(packagesOrdered, transformedClassHashes);
  }

  private final String[] packagesOrdered;
  private final int[][] transformedClassHashes;

  public Result transform(String fullClassName) {
    int packageEndsAt = fullClassName.lastIndexOf('.');
    String packageName = fullClassName.substring(0, Math.max(packageEndsAt, 0));
    int index = Arrays.binarySearch(packagesOrdered, packageName);
    if (index < 0) {
      // package not found
      return Result.UNKNOWN;
    }
    int[] transformedClassHashes = this.transformedClassHashes[index];
    if (transformedClassHashes == null) {
      // no hashes, assume all classes are skipped
      return Result.SKIP;
    }
    String className = fullClassName.substring(packageEndsAt + 1);
    return Arrays.binarySearch(transformedClassHashes, className.hashCode()) >= 0
        ? Result.TRANSFORM
        : Result.SKIP;
  }

  private MatcherCache(String[] packagesOrdered, int[][] transformedClassHashes) {
    assert packagesOrdered.length == transformedClassHashes.length;
    this.packagesOrdered = packagesOrdered;
    this.transformedClassHashes = transformedClassHashes;
  }

  private static int[] readData(InputStream is) throws IOException {
    int len = readInt(is);
    assert len >= 0;
    if (len == 0) {
      return null;
    }
    int[] hashes = new int[len];
    int prevHash = Integer.MIN_VALUE;
    for (int i = 0; i < len; i++) {
      int hash = readInt(is);
      if (hash < prevHash) {
        throw new IllegalStateException(
            "Unordered class hash detected: " + prevHash + " goes before " + prevHash);
      }
      hashes[i] = hash;
      prevHash = hash;
    }
    return hashes;
  }
}
