package datadog.trace.agent.tooling.matchercache;

import static datadog.trace.agent.tooling.matchercache.util.BinarySerializers.readInt;
import static datadog.trace.agent.tooling.matchercache.util.BinarySerializers.readString;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

public final class MatcherCache {

  public enum Result {
    TRANSFORM,
    SKIP,
    UNKNOWN,
  }

  public static MatcherCache deserialize(File file) throws IOException {
    try (FileInputStream is = new FileInputStream(file)) {
      return deserialize(is);
    }
  }

  public static MatcherCache deserialize(InputStream is) throws IOException {
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
    // TODO: try implement binary search without sub string allocation
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

  //  private static final Comparator<String> PACKAGE_COMPARATOR = new Comparator<String>() {
  //    @Override
  //    public int compare(String pkg, String fullClassName) {
  //      int i = 0;
  //      int n = pkg.length();
  //      int k = fullClassName.lastIndexOf('.');
  //      int lim = Math.min(n, k);
  //      char c1, c2;
  //      do {
  //        c1 = pkg.charAt(i);
  //        c2 = fullClassName.charAt(i);
  //        i++;
  //      } while (c1 == c2 && i < lim);
  //      int r = c1 - c2;
  //      if (r != 0) {
  //        return r;
  //      }
  //      if (i == k) {
  //        // pkg == fullClassName package matches
  //        return Integer.compare(n, k);
  //      }
  //      if (i < k) {
  //        // higher level pkg matches fullClassName package
  //        return -1;
  //      }
  //      return 1;
  //    }
  //  };
  //
  //  public boolean transform(String fullClassName) {
  //    int index = Arrays.binarySearch(packagesOrdered, fullClassName, PACKAGE_COMPARATOR);
  //    if (index < 0) {
  //      // package not found
  //      return true;
  //    }
  //    int[] transformedClassHashes = this.transformedClassHashes[index];
  //    if (transformedClassHashes == null) {
  //      // no hashes, assume all classes are skipped
  //      return false;
  //    }
  //    int h = 0;
  //    int len = fullClassName.length();
  //    for (int i = fullClassName.lastIndexOf('.') + 1; i < len; i++) {
  //      h = 31 * h + fullClassName.charAt(i);
  //    }
  //    return Arrays.binarySearch(transformedClassHashes, h) >= 0;
  //  }
}
