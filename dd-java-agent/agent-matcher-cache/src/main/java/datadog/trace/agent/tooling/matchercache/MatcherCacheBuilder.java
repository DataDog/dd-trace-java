package datadog.trace.agent.tooling.matchercache;

import datadog.trace.agent.tooling.matchercache.classfinder.ClassCollection;
import datadog.trace.agent.tooling.matchercache.classfinder.ClassData;
import datadog.trace.agent.tooling.matchercache.util.BinarySerializers;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MatcherCacheBuilder {
  static final class Stats {
    int transformedClassesCounter = 0;
    int skippedClassesCounter = 0;
    int failedToLoadCounterCounter = 0;
    int falsePositives = 0;

    @Override
    public String toString() {
      return "Transformable classes: "
          + transformedClassesCounter
          + "; Skipped classes: "
          + skippedClassesCounter
          + "; Failed to load classes: "
          + failedToLoadCounterCounter
          + "; False positives: "
          + falsePositives;
    }
  }

  private static final Logger log = LoggerFactory.getLogger(MatcherCacheBuilder.class);
  private final TreeMap<String, PackageData> packages;
  private final int javaMajorVersion;

  public MatcherCacheBuilder(int javaMajorVersion) {
    this.packages = new TreeMap<>();
    this.javaMajorVersion = javaMajorVersion;
  }

  public int getJavaMajorVersion() {
    return javaMajorVersion;
  }

  public Stats fill(
      ClassCollection classCollection, ClassLoader classLoader, ClassMatchers classMatchers) {

    Stats stats = new Stats();

    Set<ClassData> skippedClasses = new HashSet<>();

    for (ClassData classData : classCollection.allClasses(javaMajorVersion)) {
      String packageName = classData.packageName();
      String className = classData.className();
      String source = classData.source(javaMajorVersion);

      try {
        Class<?> cl = classLoader.loadClass(classData.fullClassName());

        if (classMatchers.matchesAny(cl)) {
          // TODO check if different classCollections share packages that include instrumented
          // classes and warn about it
          PackageData packageData = getDataOrCreate(packageName, source);
          packageData.insert(className, MatchingResult.TRANSFORM);
          stats.transformedClassesCounter += 1;
        } else {
          skippedClasses.add(classData);
        }
      } catch (Throwable e) {
        stats.failedToLoadCounterCounter += 1;
        PackageData packageData = getDataOrCreate(packageName, source);
        packageData.insert(className, MatchingResult.FAIL_TO_LOAD);
        stats.transformedClassesCounter += 1;
        log.debug("Couldn't load class: {} failed with {}", className, e);
      }
    }

    // insert skipped classes after matched to detect false-positives
    for (ClassData classData : skippedClasses) {
      PackageData packageData =
          getDataOrCreate(classData.packageName(), classData.source(javaMajorVersion));
      String className = classData.className();
      if (packageData.isTransformed(className)) {
        stats.falsePositives += 1;
      }
      packageData.insert(className, MatchingResult.SKIP);
      stats.skippedClassesCounter += 1;
    }

    return stats;
  }

  public void addSkippedPackage(String packageName, String source) {
    PackageData packageData = getDataOrCreate(packageName, source);
    // TODO check if package for another source already exists
    packageData.insert(packageName + ".0", MatchingResult.SKIP);
  }

  public void serializeBinary(File file) throws IOException {
    try (FileOutputStream os = new FileOutputStream(file)) {
      serializeBinary(os);
    }
  }

  public void serializeBinary(OutputStream os) throws IOException {
    int numberOfPackages = packages.size();
    BinarySerializers.writeInt(os, numberOfPackages);

    for (Map.Entry<String, PackageData> entry : packages.entrySet()) {
      BinarySerializers.writeString(os, entry.getKey());

      PackageData packageData = entry.getValue();
      writeHashCodes(os, packageData.transformedClassHashes);
    }
  }

  public void optimize() {
    ArrayList<String> pkgs = new ArrayList<>(this.packages.keySet());

    for (String pkg : pkgs) {
      PackageData packageData = this.packages.get(pkg);
      if (packageData.skippedCounter == 0
          && packageData.failedToLoadCounter == packageData.transformedClassHashes.size()) {
        this.packages.remove(pkg);
        log.debug(
            "{} removed because it has no skipped classes. (Transformed classes: {} including failed to load: {}",
            pkg,
            packageData.transformedClassHashes.size(),
            packageData.failedToLoadCounter);
      }
      // TODO consider removing packages with much more class exclusions then skipped classes to
      // minimize footprint
    }
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();

    sb.append("packages: ");
    sb.append(packages.size());
    sb.append('\n');

    ArrayList<String> pkgs = new ArrayList<>(packages.keySet());
    Collections.sort(pkgs);
    for (String pkg : pkgs) {
      PackageData packageData = packages.get(pkg);
      sb.append(packageData.transformedClassHashes.size());
      sb.append(',');
      sb.append(packageData.skippedCounter);
      sb.append(',');
      sb.append(packageData.failedToLoadCounter);
      sb.append(',');
      sb.append(pkg);
      sb.append('\n');
    }

    return sb.toString();
  }

  protected int classHash(String className) {
    return className.hashCode();
  }

  private int classNameStartsAt(String fqcn) {
    int classNameStartsAt = fqcn.lastIndexOf('.');
    return Math.max(classNameStartsAt, 0);
  }

  private PackageData getDataOrCreate(String packageName, String source) {
    PackageData packageData = packages.get(packageName);
    if (packageData == null) {
      packageData = new PackageData(source);
      packages.put(packageName, packageData);
    }
    return packageData;
  }

  private void writeHashCodes(OutputStream os, Set<Integer> hashCodes) throws IOException {
    if (hashCodes == null) {
      BinarySerializers.writeInt(os, 0);
      return;
    }
    int hashCodesSize = hashCodes.size();
    // number of class hash codes
    BinarySerializers.writeInt(os, hashCodesSize);
    // class hash codes
    Iterator<Integer> iterator = hashCodes.iterator();
    for (int i = 0; i < hashCodesSize; i++) {
      int hash = iterator.next();
      BinarySerializers.writeInt(os, hash);
    }
  }

  private enum MatchingResult {
    TRANSFORM,
    SKIP,
    FAIL_TO_LOAD,
  }

  private final class PackageData {

    private final String source;
    private final TreeSet<Integer> transformedClassHashes;
    private int failedToLoadCounter;
    private int skippedCounter;
    private int falsePositives;

    public PackageData(String source) {
      this.source = source;
      transformedClassHashes = new TreeSet<>();
    }

    public void insert(String className, MatchingResult mr) {
      int classHash = classHash(className);
      switch (mr) {
        case TRANSFORM:
          transformedClassHashes.add(classHash);
          break;
        case SKIP:
          if (transformedClassHashes.contains(classHash)) {
            falsePositives += 1;
          }
          skippedCounter += 1;
          break;
        case FAIL_TO_LOAD:
          transformedClassHashes.add(classHash);
          failedToLoadCounter += 1;
          break;
      }
    }

    public boolean isTransformed(String className) {
      int hash = classHash(className);
      return transformedClassHashes.contains(hash);
    }
  }
}
