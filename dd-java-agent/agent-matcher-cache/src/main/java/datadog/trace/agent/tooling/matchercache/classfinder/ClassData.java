package datadog.trace.agent.tooling.matchercache.classfinder;

import java.util.ArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ClassData {
  private static final String META_INF_VERSIONS_PATH_PREFIX = "META-INF/versions/";
  private static final Logger log = LoggerFactory.getLogger(ClassData.class);

  private final String fullClassName;
  private final int classNameStartsAt;
  private final ArrayList<ClassVersion> versions;

  public ClassData(String fullClassName) {
    classNameStartsAt = fullClassName.lastIndexOf('.');
    this.fullClassName = fullClassName;
    versions = new ArrayList<>();
  }

  public String getFullClassName() {
    return fullClassName;
  }

  public String packageName() {
    return fullClassName.substring(0, Math.max(classNameStartsAt, 0));
  }

  public String className() {
    return fullClassName.substring(classNameStartsAt < 0 ? 0 : classNameStartsAt + 1);
  }

  public String location(int jdkMajorVersion) {
    ClassVersion cv = classVersion(jdkMajorVersion);
    return cv == null ? null : cv.getLocation();
  }

  public void addClassBytes(byte[] classBytes, String relativePath, String parentPath) {
    int jdkMajorVersion = parseJdkMajorVersionFromClassPath(relativePath);
    ClassVersion classVersion = new ClassVersion(parentPath, classBytes, jdkMajorVersion);
    int insertAt = findInsertPos(jdkMajorVersion);
    versions.add(insertAt, classVersion);
  }

  public byte[] classBytes(int javaMajorVersion) {
    ClassVersion classVersion = classVersion(javaMajorVersion);
    if (classVersion == null) {
      return null;
    }
    return classVersion.classBytes;
  }

  private int parseJdkMajorVersionFromClassPath(String classPath) {
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

  private ClassVersion classVersion(int javaMajorVersion) {
    int pos = findInsertPos(javaMajorVersion);
    if (pos > 0) {
      return versions.get(pos - 1);
    }
    return null;
  }

  private int findInsertPos(int jdkMajorVersion) {
    int insertAt = 0;
    while (insertAt < versions.size() && versions.get(insertAt).version <= jdkMajorVersion) {
      insertAt += 1;
    }
    return insertAt;
  }

  private static final class ClassVersion {
    private final String parentPath;
    private final byte[] classBytes;
    private final int version;

    private ClassVersion(String parentPath, byte[] classBytes, int version) {
      this.parentPath = parentPath;
      this.classBytes = classBytes;
      this.version = version;
    }

    public String getLocation() {
      return parentPath;
    }
  }
}
