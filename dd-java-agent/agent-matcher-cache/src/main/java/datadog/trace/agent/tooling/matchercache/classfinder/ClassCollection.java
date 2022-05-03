package datadog.trace.agent.tooling.matchercache.classfinder;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ClassCollection {
  private final Map<String, ClassData> classMap = new HashMap<>();

  public void addClass(byte[] classBytes, String fqcn, String relativePath, String parentPath) {
    ClassData classData = classMap.get(fqcn);
    if (classData == null) {
      classData = new ClassData(fqcn);
      classMap.put(fqcn, classData);
    }
    classData.addClassBytes(classBytes, relativePath, parentPath);
  }

  public ClassData findClass(String className) {
    return classMap.get(className);
  }

  public Set<ClassData> allClasses(int majorJavaVersion) {
    Set<ClassData> classes = new HashSet<>();
    for (ClassData classData : classMap.values()) {
      if (classData.classBytes(majorJavaVersion) != null) {
        classes.add(classData);
      }
    }
    return classes;
  }
}
