package datadog.trace.agent.tooling.matchercache.classfinder;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ClassCollection {
  private final Map<String, ClassVersions> classMap = new HashMap<>();

  public void addClass(byte[] classBytes, String fqcn, String relativePath, String parentPath) {
    ClassVersions classVersions = classMap.get(fqcn);
    if (classVersions == null) {
      classVersions = new ClassVersions(fqcn);
      classMap.put(fqcn, classVersions);
    }
    classVersions.addClassBytes(classBytes, relativePath, parentPath);
  }

  public ClassVersions findClass(String className) {
    return classMap.get(className);
  }

  public Set<ClassVersions> allClasses(int majorJavaVersion) {
    Set<ClassVersions> classes = new HashSet<>();
    for (ClassVersions classVersions : classMap.values()) {
      if (classVersions.classBytes(majorJavaVersion) != null) {
        classes.add(classVersions);
      }
    }
    return classes;
  }
}
