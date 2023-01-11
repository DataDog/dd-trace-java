package datadog.compiler;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;

public class InMemoryFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {

  private final Map<String, InMemoryClassFile> compiledClasses = new HashMap<>();

  private final ClassLoader classLoader =
      new ClassLoader() {
        @Override
        protected Class<?> findClass(String name) {
          byte[] byteCode = compiledClasses.get(name).getCompiledBinaries();
          return defineClass(name, byteCode, 0, byteCode.length);
        }
      };

  protected InMemoryFileManager(StandardJavaFileManager fileManager) {
    super(fileManager);
  }

  @Override
  public JavaFileObject getJavaFileForOutput(
      Location location, String className, JavaFileObject.Kind kind, FileObject sibling) {
    InMemoryClassFile result = new InMemoryClassFile(URI.create("string://" + className));
    compiledClasses.put(className, result);
    return result;
  }

  public Class<?> loadCompiledClass(String className) throws ClassNotFoundException {
    return classLoader.loadClass(className);
  }
}
