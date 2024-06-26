package datadog.trace.civisibility.source;

import java.io.IOException;
import java.io.InputStream;
import javax.annotation.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class Utils {

  private static final Logger log = LoggerFactory.getLogger(Utils.class);

  @Nullable
  public static InputStream getClassStream(Class<?> clazz) throws IOException {
    String className = clazz.getName();
    InputStream classStream = clazz.getResourceAsStream(toResourceName(className));
    if (classStream != null) {
      return classStream;
    } else {
      // might be auto-generated inner class (e.g. Mockito mock)
      String topLevelClassName = stripNestedClassNames(clazz.getName());
      return clazz.getResourceAsStream(toResourceName(topLevelClassName));
    }
  }

  @Nullable
  public static String getFileName(Class<?> clazz) throws IOException {
    SourceFileAttributeVisitor visitor = new SourceFileAttributeVisitor();
    try (InputStream classStream = Utils.getClassStream(clazz)) {
      if (classStream == null) {
        log.debug("Could not get input stream for class {}", clazz.getName());
        return null;
      }
      ClassReader classReader = new ClassReader(classStream);
      classReader.accept(visitor, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
      return visitor.getSource();
    }
  }

  private static final class SourceFileAttributeVisitor extends ClassVisitor {
    private String source;

    SourceFileAttributeVisitor() {
      super(Opcodes.ASM9);
    }

    @Override
    public void visitSource(String source, String debug) {
      this.source = source;
    }

    public String getSource() {
      return source;
    }
  }

  private static String toResourceName(String className) {
    return "/" + className.replace('.', '/') + ".class";
  }

  public static String stripNestedClassNames(String className) {
    int innerClassNameIdx = className.indexOf('$');
    if (innerClassNameIdx >= 0) {
      return className.substring(0, innerClassNameIdx);
    } else {
      return className;
    }
  }
}
