package datadog.trace.agent.tooling.matchercache.classfinder;

import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ClassVersions {
  private static final String META_INF_VERSIONS_PATH_PREFIX = "META-INF/versions/";
  private static final Logger log = LoggerFactory.getLogger(ClassVersions.class);

  private final String fqcn;
  private final int classNameStartsAt;
  private ClassData defaultVersion;
  private TreeMap<Integer, ClassData> otherVersions;

  public ClassVersions(String fqcn) {
    classNameStartsAt = fqcn.lastIndexOf('.');
    this.fqcn = fqcn;
  }

  public String fullClassName() {
    return fqcn;
  }

  public String packageName() {
    return fqcn.substring(0, Math.max(classNameStartsAt, 0));
  }

  public String className() {
    return fqcn.substring(classNameStartsAt < 0 ? 0 : classNameStartsAt + 1);
  }

  public String location(int jdkMajorVersion) {
    ClassData cv = classVersion(jdkMajorVersion);
    return cv == null ? null : cv.location;
  }

  public void addClassBytes(byte[] classBytes, String classPath, String parentPath) {
    ClassData classData = new ClassData(parentPath, classBytes);
    int version = getClassVersion(classPath);
    if (version == 0) {
      if (checkNoConflictingClassVersions(defaultVersion, classData)) {
        defaultVersion = classData;
      }
    } else {
      if (otherVersions == null) {
        otherVersions = new TreeMap<>();
      }
      ClassData existingClassData = otherVersions.get(version);
      if (checkNoConflictingClassVersions(existingClassData, classData)) {
        otherVersions.put(version, classData);
      }
    }
  }

  public byte[] classBytes(int jdkMajorVersion) {
    ClassData classData = classVersion(jdkMajorVersion);
    if (classData == null) {
      return null;
    }
    return classData.classBytes;
  }

  private ClassData classVersion(int jdkMajorVersion) {
    if (otherVersions != null) {
      int useVersion = 0;
      for (int classVersion : otherVersions.keySet()) {
        if (classVersion <= jdkMajorVersion) {
          useVersion = classVersion;
        } else {
          break;
        }
      }
      ClassData classData = otherVersions.get(useVersion);
      if (classData != null) {
        return classData;
      }
    }
    if (defaultVersion != null) {
      return defaultVersion;
    }
    return null;
  }

  private boolean checkNoConflictingClassVersions(ClassData existing, ClassData other) {
    if (existing != null && !other.equals(existing)) {
      log.debug(
          "Detected conflicting class version: {} and {}. Using existing version.",
          existing,
          other);
      return false;
    }
    return true;
  }

  private int getClassVersion(String classPath) {
    int version = 0;
    if (classPath.startsWith(META_INF_VERSIONS_PATH_PREFIX)) {
      try {
        int versionStartsAt = META_INF_VERSIONS_PATH_PREFIX.length();
        int versionEndsAt = classPath.indexOf('/', versionStartsAt);
        String versionStr = classPath.substring(versionStartsAt, versionEndsAt);
        version = Integer.parseInt(versionStr);
      } catch (NumberFormatException | IndexOutOfBoundsException e) {
        log.warn("Couldn't parse class version: {}. Using default version.", classPath);
      }
    }
    return version;
  }

  private static final class ClassData {
    private final String location;
    private final byte[] classBytes;

    private ClassData(String location, byte[] classBytes) {
      this.location = location;
      this.classBytes = classBytes;
    }
  }
}
