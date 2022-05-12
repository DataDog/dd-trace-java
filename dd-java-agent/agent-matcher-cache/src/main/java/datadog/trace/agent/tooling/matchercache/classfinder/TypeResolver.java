package datadog.trace.agent.tooling.matchercache.classfinder;

import java.io.IOException;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.pool.TypePool;

public class TypeResolver {

  public static final class ClassNotFound extends RuntimeException {
    public ClassNotFound(String fullClassName) {
      super("Failed to resolve class " + fullClassName);
    }
  }

  public static final class VersionNotFound extends RuntimeException {
    public VersionNotFound(String fullClassName, int javaMajorVersion) {
      super("Failed to resolve class " + fullClassName + " for Java " + javaMajorVersion);
    }
  }

  private final TypePool typePool;

  public TypeResolver(ClassCollection classCollection, int javaMajorVersion) {
    CollectionClassLocator classLocator =
        new CollectionClassLocator(classCollection, javaMajorVersion);
    this.typePool = TypePool.Default.of(classLocator);
  }

  public TypeDescription typeDescription(String fullClassName) {
    return typePool.describe(fullClassName).resolve();
  }

  private static class CollectionClassLocator implements ClassFileLocator {
    private final ClassCollection classCollection;
    private final int javaMajorVersion;

    private CollectionClassLocator(ClassCollection classCollection, int javaMajorVersion) {
      this.classCollection = classCollection;
      this.javaMajorVersion = javaMajorVersion;
    }

    @Override
    public Resolution locate(String name) {
      byte[] bytes = classBytes(name);
      return new Resolution.Explicit(bytes);
    }

    private byte[] classBytes(String fullClassName) {
      ClassData classData = classCollection.findClassData(fullClassName);
      if (classData == null) {
        throw new ClassNotFound(fullClassName);
      }
      byte[] bytes = classData.classBytes(javaMajorVersion);
      if (bytes == null) {
        throw new VersionNotFound(fullClassName, javaMajorVersion);
      }
      return bytes;
    }

    @Override
    public void close() throws IOException {}
  }
}
