package datadog.trace.agent.tooling.matchercache.classfinder;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class ClassCollection {
  public static class Builder {

    private Map<String, ClassData> classMap = new HashMap<>();

    public void addClass(
        byte[] classBytes, String fullClassName, String relativePath, String parentPath) {
      ClassData classData = classMap.get(fullClassName);
      if (classData == null) {
        classData = new ClassData(fullClassName);
        classMap.put(fullClassName, classData);
      }
      classData.addClassBytes(classBytes, relativePath, parentPath);
    }

    public ClassCollection buildAndReset() {
      Map<String, ClassData> cm = Collections.unmodifiableMap(classMap);
      classMap = new HashMap<>();
      return new ClassCollection(cm, null);
    }
  }

  private final Map<String, ClassData> classMap;
  private final ClassCollection parent;

  private ClassCollection(Map<String, ClassData> classMap, ClassCollection parent) {
    this.classMap = classMap;
    this.parent = parent;
  }

  public ClassData findClassData(String className) {
    if (parent != null) {
      ClassData classData = parent.findClassData(className);
      if (classData != null) {
        return classData;
      }
    }
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

  public ClassCollection withParent(ClassCollection parent) {
    if (parent == null) {
      return this;
    }
    return new ClassCollection(classMap, parent);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ClassCollection that = (ClassCollection) o;
    return Objects.equals(classMap, that.classMap) && Objects.equals(parent, that.parent);
  }

  @Override
  public int hashCode() {
    return Objects.hash(classMap, parent);
  }
}
