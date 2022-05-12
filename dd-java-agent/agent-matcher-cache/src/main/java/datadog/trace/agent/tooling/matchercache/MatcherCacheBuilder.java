package datadog.trace.agent.tooling.matchercache;

import datadog.trace.agent.tooling.matchercache.classfinder.ClassCollection;
import datadog.trace.agent.tooling.matchercache.classfinder.ClassData;
import datadog.trace.agent.tooling.matchercache.classfinder.TypeResolver;
import datadog.trace.agent.tooling.matchercache.util.BinarySerializers;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.*;
import net.bytebuddy.description.type.TypeDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MatcherCacheBuilder {
  public static final int MATCHER_CACHE_FILE_FORMAT_VERSION = 1;

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
  private final ClassMatchers classMatchers;
  private final int javaMajorVersion;
  private final String agentVersion;
  private final TreeMap<String, PackageData> packages;

  public MatcherCacheBuilder(
      ClassMatchers classMatchers, int javaMajorVersion, String agentVersion) {
    this.classMatchers = classMatchers;
    this.packages = new TreeMap<>();
    this.javaMajorVersion = javaMajorVersion;
    this.agentVersion = agentVersion;
  }

  public Stats fill(ClassCollection classCollection) {
    Stats stats = new Stats();
    TypeResolver typeResolver = getTypeResolver(classCollection);
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
          PackageData packageData = getDataOrCreate(packageName);
          TypeDescription typeDescription =
              typeResolver.typeDescription(classData.getFullClassName());
          if (classMatchers.matchesAny(typeDescription)) {
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

  protected TypeResolver getTypeResolver(ClassCollection classCollection) {
    return new TypeResolver(classCollection, javaMajorVersion);
  }

  public void serializeBinary(File file) throws IOException {
    try (FileOutputStream os = new FileOutputStream(file)) {
      serializeBinary(os);
    }
  }

  public void serializeBinary(OutputStream os) throws IOException {
    BinarySerializers.writeInt(os, MATCHER_CACHE_FILE_FORMAT_VERSION);
    BinarySerializers.writeInt(os, javaMajorVersion);
    BinarySerializers.writeString(os, agentVersion);
    int numberOfPackages = 0;
    for (Map.Entry<String, PackageData> entry : packages.entrySet()) {
      if (entry.getValue().canBeRemoved()) {
        log.info(
            "Package {} will be excluded from the matcher cache, because all its classes can't be skipped",
            entry.getKey());
      } else {
        numberOfPackages += 1;
      }
    }
    BinarySerializers.writeInt(os, numberOfPackages);
    for (Map.Entry<String, PackageData> entry : packages.entrySet()) {
      PackageData packageData = entry.getValue();
      if (!packageData.canBeRemoved()) {
        BinarySerializers.writeString(os, entry.getKey());
        writeHashCodes(os, packageData.transformedClassHashes());
      }
    }
  }

  public void serializeText(File file) throws IOException {
    try (FileOutputStream os = new FileOutputStream(file)) {
      serializeText(os);
    }
  }

  public void serializeText(OutputStream os) {
    PrintStream ps = new PrintStream(os);
    ps.println("Matcher Cache Report");
    ps.print("Format Version: ");
    ps.println(MATCHER_CACHE_FILE_FORMAT_VERSION);
    ps.print("Agent Version: ");
    ps.println(agentVersion);
    ps.print("Java Major Version: ");
    ps.println(javaMajorVersion);
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
        if (pd.canBeRemoved()) {
          ps.print(',');
          ps.print(
              "Package excluded from the matcher cache, because all its classes can't be skipped");
        }
        ps.println();
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
