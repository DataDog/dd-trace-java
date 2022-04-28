package datadog.trace.agent.tooling.matchercache.classfinder;

import java.util.Arrays;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ClassData {
  private static final String META_INF_VERSIONS_PATH_PREFIX = "META-INF/versions/";
  private static final Logger log = LoggerFactory.getLogger(ClassData.class);

  private final String fqcn;
  private final int classNameStartsAt;
  private ClassVersion defaultVersion;
  private TreeMap<Integer, ClassVersion> otherVersions;

  public ClassData(String fqcn) {
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

  public String source(int jdkMajorVersion) {
    ClassVersion classVersion = classVersion(jdkMajorVersion);
    if (classVersion == null) {
      return null;
    }
    return classVersion.parentPath;
  }

  public void addClassBytes(byte[] classBytes, String classPath, String parentPath) {
    ClassVersion classVersion = new ClassVersion(parentPath, classPath, classBytes);
    int version = getClassVersion(classPath);
    if (version == 0) {
      if (checkNoConflictingClassVersions(defaultVersion, classVersion)) {
        defaultVersion = classVersion;
      }
    } else {
      if (otherVersions == null) {
        otherVersions = new TreeMap<>();
      }
      ClassVersion existingClassVersion = otherVersions.get(version);
      if (checkNoConflictingClassVersions(existingClassVersion, classVersion)) {
        otherVersions.put(version, classVersion);
      }
    }
  }

  public byte[] classBytes(int jdkMajorVersion) {
    ClassVersion classVersion = classVersion(jdkMajorVersion);
    if (classVersion == null) {
      return null;
    }
    return classVersion.classBytes;
  }

  private ClassVersion classVersion(int jdkMajorVersion) {
    if (otherVersions != null) {
      int useVersion = 0;
      for (int classVersion : otherVersions.keySet()) {
        if (classVersion <= jdkMajorVersion) {
          useVersion = classVersion;
        } else {
          break;
        }
      }
      ClassVersion classVersion = otherVersions.get(useVersion);
      if (classVersion != null) {
        return classVersion;
      }
    }
    if (defaultVersion != null) {
      return defaultVersion;
    }
    return null;
  }

  private boolean checkNoConflictingClassVersions(ClassVersion existing, ClassVersion other) {
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

  private final class ClassVersion {
    private final String parentPath;
    private final String relativePath;
    private final byte[] classBytes;

    private ClassVersion(String parentPath, String relativePath, byte[] classBytes) {
      this.parentPath = parentPath;
      this.relativePath = relativePath;
      this.classBytes = classBytes;
    }

    @Override
    public String toString() {
      return fqcn
          + " from "
          + parentPath
          + "/"
          + relativePath
          + " ("
          + Arrays.hashCode(classBytes)
          + ")";
    }
  }
}
