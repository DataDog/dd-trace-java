package utils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;

public final class MemoryJavaFileManager extends ForwardingJavaFileManager<JavaFileManager> {

  private Map<String, byte[]> classBytes;

  public MemoryJavaFileManager(JavaFileManager fileManager) {
    super(fileManager);
    classBytes = new HashMap<>();
  }

  static URI toURI(String name) {
    File file = new File(name);
    if (file.exists()) {
      return file.toURI();
    } else {
      try {
        return URI.create("mfm:///" + name);
      } catch (Exception exp) {
        return URI.create("mfm:///org/openjdk/btrace/script/java/java_source");
      }
    }
  }

  public Map<String, byte[]> getClassBytes() {
    return classBytes;
  }

  @Override
  public void close() throws IOException {
    classBytes = new HashMap<>();
  }

  @Override
  public void flush() throws IOException {}

  @Override
  public JavaFileObject getJavaFileForOutput(
      JavaFileManager.Location location,
      String className,
      JavaFileObject.Kind kind,
      FileObject sibling)
      throws IOException {
    if (kind == JavaFileObject.Kind.CLASS) {
      return new ClassOutputBuffer(className);
    } else {
      return super.getJavaFileForOutput(location, className, kind, sibling);
    }
  }

  /** A file object used to represent Java source coming from a string. */

  /** A file object that stores Java bytecode into the classBytes map. */
  private class ClassOutputBuffer extends SimpleJavaFileObject {

    private final String name;

    ClassOutputBuffer(String name) {
      super(toURI(name), Kind.CLASS);
      this.name = name;
    }

    @Override
    public OutputStream openOutputStream() {
      return new FilterOutputStream(new ByteArrayOutputStream()) {

        @Override
        public void close() throws IOException {
          out.close();
          ByteArrayOutputStream bos = (ByteArrayOutputStream) out;
          classBytes.put(name, bos.toByteArray());
        }
      };
    }
  }
}
