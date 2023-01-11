package datadog.compiler;

import java.net.URI;
import javax.tools.SimpleJavaFileObject;

public class InMemorySourceFile extends SimpleJavaFileObject {
  private static final String FILE_PROTOCOL = "file://";
  private static final String FAKE_REPO_ROOT = "/repo/src/";

  private final String content;

  public InMemorySourceFile(String qualifiedClassName, String testSource) {
    super(URI.create(FILE_PROTOCOL + sourcePath(qualifiedClassName)), Kind.SOURCE);
    content = testSource;
  }

  @Override
  public CharSequence getCharContent(boolean ignoreEncodingErrors) {
    return content;
  }

  public static String sourcePath(String qualifiedClassName) {
    return String.format(
        FAKE_REPO_ROOT + "%s%s", qualifiedClassName.replaceAll("\\.", "/"), Kind.SOURCE.extension);
  }
}
