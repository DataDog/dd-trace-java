package datadog.trace.civisibility.source.index

import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import spock.lang.Specification

import java.nio.file.Files

class SourceRootResolverImplTest extends Specification {

  def "test source root resolution"() {
    setup:
    def fileSystem = Jimfs.newFileSystem(Configuration.unix())
    def javaFilePath = fileSystem.getPath(path)

    Files.createDirectories(javaFilePath.getParent())
    Files.write(javaFilePath, contents.getBytes())

    when:
    def sourceRootResolver = new SourceRootResolverImpl(fileSystem)
    def sourceRoot = sourceRootResolver.getSourceRoot(javaFilePath)

    then:
    sourceRoot == fileSystem.getPath(expectedSourceRoot)

    where:
    path                             | contents                                      | expectedSourceRoot
    "/root/src/MyClass.java"         | CLASS_IN_DEFAULT_PACKAGE                      | "/root/src"
    "/root/src/foo/bar/MyClass.java" | CLASS_IN_FOO_BAR_PACKAGE                      | "/root/src"
    "/root/src/foo/bar/MyClass.java" | BLANK_LINES_BEFORE_PACKAGE                    | "/root/src"
    "/root/src/foo/bar/MyClass.java" | SPACES_BEFORE_PACKAGE                         | "/root/src"
    "/root/src/foo/bar/MyClass.java" | COMMENT_BEFORE_PACKAGE                        | "/root/src"
    "/root/src/foo/bar/MyClass.java" | COMMENT_WITH_KEYWORD_BEFORE_PACKAGE           | "/root/src"
    "/root/src/foo/bar/MyClass.java" | MULTILINE_COMMENT_BEFORE_PACKAGE              | "/root/src"
    "/root/src/foo/bar/MyClass.java" | MULTILINE_COMMENT_WITH_KEYWORD_BEFORE_PACKAGE | "/root/src"
  }

  private static final String CLASS_IN_DEFAULT_PACKAGE =
  "public class MyClass {}"

  private static final String CLASS_IN_FOO_BAR_PACKAGE =
  "package foo.bar;\n" +
  "public class MyClass {}"

  private static final String BLANK_LINES_BEFORE_PACKAGE =
  "\n\n" +
  "package foo.bar;\n" +
  "public class MyClass {}"

  private static final String SPACES_BEFORE_PACKAGE =
  "      package    foo.bar;\n" +
  "public class MyClass {}"

  private static final String COMMENT_BEFORE_PACKAGE =
  "// this is a comment \n" +
  "package foo.bar;\n" +
  "public class MyClass {}"

  private static final String COMMENT_WITH_KEYWORD_BEFORE_PACKAGE =
  "// this is a comment with package bar.baz; \n" +
  "package foo.bar;\n" +
  "public class MyClass {}"

  private static final String MULTILINE_COMMENT_BEFORE_PACKAGE =
  "/* \n" +
  " * this is a multiline comment \n" +
  " */ \n" +
  "package foo.bar;\n" +
  "public class MyClass {}"

  private static final String MULTILINE_COMMENT_WITH_KEYWORD_BEFORE_PACKAGE =
  "/* \n" +
  " * this is a multiline comment with package bar.baz; \n" +
  " */ \n" +
  "package foo.bar;\n" +
  "public class MyClass {}"
}

