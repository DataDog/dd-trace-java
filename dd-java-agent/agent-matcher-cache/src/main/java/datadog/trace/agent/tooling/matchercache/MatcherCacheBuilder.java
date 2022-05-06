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
    int counterIgnore = 0;
    int counterSkip = 0;
    int counterTransform = 0;
    int counterFail = 0;

    @Override
    public String toString() {
      return "Ignore: "
          + counterIgnore
          + "; Skip: "
          + counterSkip
          + "; Transform: "
          + counterTransform
          + "; Fail: "
          + counterFail;
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
      String location = classData.location(javaMajorVersion);

      if (classMatchers.isGloballyIgnored(classData.getFullClassName())) {
        PackageData packageData = getDataOrCreate(packageName);
        packageData.insert(className, MatchingResult.IGNORE, location, null);
        stats.counterIgnore += 1;
      } else
        try {
          Class<?> cl = classLoader.loadClass(classData.getFullClassName());
          PackageData packageData = getDataOrCreate(packageName);
          if (classMatchers.matchesAny(cl)) {
            // TODO check if different classCollections share packages that include instrumented
            // classes and warn about it, and maybe exclude from the matcher cache
            packageData.insert(className, MatchingResult.TRANSFORM, location, null);
            stats.counterTransform += 1;
          } else {
            packageData.insert(className, MatchingResult.SKIP, location, null);
            stats.counterSkip += 1;
          }
        } catch (Throwable e) {
          stats.counterFail += 1;
          PackageData packageData = getDataOrCreate(packageName);
          packageData.insert(className, MatchingResult.FAIL, location, e.toString());
          log.debug("Couldn't load class: {} failed with {}", className, e.toString());
        }
    }
    return stats;
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
      for (Map.Entry<String, ClassInfo> classEntry : pd.classes.entrySet()) {
        if (!packageName.isEmpty()) {
          ps.print(packageName);
          ps.print('.');
        }
        String className = classEntry.getKey();
        ps.print(className);
        ps.print(',');
        ClassInfo ci = classEntry.getValue();
        ps.print(ci.matchingResult);
        ps.print(',');
        ps.print(ci.classLocation);
        if (ci.additionalInfo != null) {
          ps.print(',');
          ps.print(ci.additionalInfo);
        }
        ps.println();
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

  private PackageData getDataOrCreate(String packageName) {
    PackageData packageData = packages.get(packageName);
    if (packageData == null) {
      packageData = new PackageData();
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
    // this ordering matters, listed by priority
    TRANSFORM,
    SKIP,
    IGNORE,
    FAIL,
  }

  private static final class ClassInfo {
    private final MatchingResult matchingResult;
    private final String classLocation;
    private final String additionalInfo;

    public ClassInfo(MatchingResult matchingResult, String classLocation, String additionalInfo) {
      this.matchingResult = matchingResult;
      this.classLocation = classLocation;
      this.additionalInfo = additionalInfo;
    }
  }

  private static final class PackageData {
    private final SortedMap<String, ClassInfo> classes;

    public PackageData() {
      this.classes = new TreeMap<>();
    }

    public void insert(
        String className, MatchingResult mr, String classLocation, String additionalInfo) {
      ClassInfo classInfo = classes.get(className);
      if (classInfo == null || mr.compareTo(classInfo.matchingResult) < 0) {
        classInfo = new ClassInfo(mr, classLocation, additionalInfo);
        classes.put(className, classInfo);
      }
    }

    public boolean canBeRemoved() {
      int skippedCounter = 0;
      int failedToLoadCounter = 0;
      int transformedCounter = 0;
      for (ClassInfo css : classes.values()) {
        switch (css.matchingResult) {
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
      for (Map.Entry<String, ClassInfo> entry : classes.entrySet()) {
        if (isTransformed(entry.getValue().matchingResult)) {
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
