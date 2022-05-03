package datadog.trace.agent.tooling.matchercache;

import datadog.trace.agent.tooling.matchercache.classfinder.ClassCollection;
import datadog.trace.agent.tooling.matchercache.classfinder.ClassData;
import datadog.trace.agent.tooling.matchercache.util.BinarySerializers;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MatcherCacheBuilder {
  static final class Stats {
    int ignoredClassesCounter = 0;
    int skippedClassesCounter = 0;
    int transformedClassesCounter = 0;
    int failedCounterCounter = 0;

    @Override
    public String toString() {
      return "Ignored: "
          + ignoredClassesCounter
          + "; Skipped: "
          + skippedClassesCounter
          + "; Transformed: "
          + transformedClassesCounter
          + "; Failed: "
          + failedCounterCounter;
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
    for (ClassData classData : classCollection.allClasses(javaMajorVersion)) {
      String packageName = classData.packageName();
      String className = classData.className();
      String source = classData.source(javaMajorVersion);

      if (classMatchers.isGloballyIgnored(classData.fullClassName())) {
        PackageData packageData = getDataOrCreate(packageName, source);
        packageData.insert(className, MatchingResult.IGNORE);
        stats.ignoredClassesCounter += 1;
      } else
        try {
          Class<?> cl = classLoader.loadClass(classData.fullClassName());
          PackageData packageData = getDataOrCreate(packageName, source);
          if (classMatchers.matchesAny(cl)) {
            // TODO check if different classCollections share packages that include instrumented
            // classes and warn about it and maybe exclude from the matcher cache
            packageData.insert(className, MatchingResult.TRANSFORM);
            stats.transformedClassesCounter += 1;
          } else {
            packageData.insert(className, MatchingResult.SKIP);
            stats.skippedClassesCounter += 1;
          }
        } catch (Throwable e) {
          stats.failedCounterCounter += 1;
          PackageData packageData = getDataOrCreate(packageName, source);
          packageData.insert(className, MatchingResult.FAIL);
          log.debug("Couldn't load class: {} failed with {}", className, e);
        }
    }
    return stats;
  }

  public void addSkippedPackage(String packageName, String source) {
    // TODO remove it if globalIgnores cover this
    PackageData packageData = getDataOrCreate(packageName, source);
    // TODO check if package for another source already exists
    packageData.insert(packageName + ".0", MatchingResult.IGNORE);
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
      writeHashCodes(os, packageData.transformedClassHashes());
    }
  }

  // TODO test
  public void serializeText(File file) throws IOException {
    try (FileOutputStream os = new FileOutputStream(file)) {
      serializeText(os);
    }
  }

  public void serializeText(OutputStream os) {
    PrintStream ps = new PrintStream(os);
    ps.print("Packages: ");
    ps.println(packages.size());
    for (Map.Entry<String, PackageData> packageEntry : packages.entrySet()) {
      String packageName = packageEntry.getKey();
      PackageData pd = packageEntry.getValue();
      for (Map.Entry<String, MatchingResult> classEntry : pd.classes.entrySet()) {
        if (!packageName.isEmpty()) {
          ps.print(packageName);
          ps.print('.');
        }
        String className = classEntry.getKey();
        ps.print(className);
        ps.print(',');
        ps.print(classEntry.getValue());
        ps.print(',');
        ps.println(pd.source);
      }
    }
  }

  public void optimize() {
    Collection<String> packageNames = new ArrayList<>(this.packages.keySet());
    for (String pkg : packageNames) {
      PackageData packageData = this.packages.get(pkg);
      if (packageData.canBeRemoved()) {
        this.packages.remove(pkg);
        log.debug("{} removed because it has no skipped classes.", pkg);
      }
    }
  }

  protected int classHash(String className) {
    return className.hashCode();
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
    IGNORE,
    SKIP,
    TRANSFORM,
    FAIL,
  }

  private static final class PackageData {
    private final String source;
    private final SortedMap<String, MatchingResult> classes;

    public PackageData(String source) {
      this.source = source;
      this.classes = new TreeMap<>();
    }

    public void insert(String className, MatchingResult mr) {
      classes.put(className, mr);
    }

    public boolean canBeRemoved() {
      int skippedCounter = 0;
      int failedToLoadCounter = 0;
      int transformedCounter = 0;
      for (MatchingResult mr : classes.values()) {
        switch (mr) {
          case IGNORE:
          case SKIP:
            skippedCounter += 1;
            break;
          case TRANSFORM:
            transformedCounter += 1;
            break;
          case FAIL:
            failedToLoadCounter += 1;
            break;
        }
      }
      return skippedCounter == 0 && failedToLoadCounter == transformedCounter;
    }

    public SortedSet<Integer> transformedClassHashes() {
      SortedSet<Integer> result = new TreeSet<>();
      for (Map.Entry<String, MatchingResult> entry : classes.entrySet()) {
        if (isTransformed(entry.getValue())) {
          result.add(entry.getKey().hashCode());
        }
      }
      return result;
    }

    private boolean isTransformed(MatchingResult matchingResult) {
      switch (matchingResult) {
        case IGNORE:
        case SKIP:
          return false;
      }
      return true;
    }
  }
}
