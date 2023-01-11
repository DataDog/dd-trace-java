package datadog.compiler;

import static java.util.Collections.singletonList;

import java.io.StringWriter;
import java.util.Arrays;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.Assert;
import org.junit.Test;

public class DatadogCompilerPluginTest {

  @Test
  public void testSourcePathInjection() throws Exception {
    String testClassName = "datadog.compiler.Test";
    String testClassSource = "package datadog.compiler; public class Test {}";
    try (InMemoryFileManager fileManager = compile(testClassName, testClassSource)) {
      Class<?> clazz = fileManager.loadCompiledClass(testClassName);
      String sourcePath = CompilerUtils.getSourcePath(clazz);
      Assert.assertEquals(InMemorySourceFile.sourcePath(testClassName), sourcePath);
    }
  }

  @Test
  public void testInnerClassSourcePathInjection() throws Exception {
    String testClassName = "datadog.compiler.Test";
    String testClassSource =
        "package datadog.compiler; public class Test { public static final class Inner {} }";
    try (InMemoryFileManager fileManager = compile(testClassName, testClassSource)) {
      Class<?> innerClazz = fileManager.loadCompiledClass(testClassName + "$Inner");
      String sourcePath = CompilerUtils.getSourcePath(innerClazz);
      Assert.assertEquals(InMemorySourceFile.sourcePath(testClassName), sourcePath);
    }
  }

  private InMemoryFileManager compile(String className, String classSource) {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    StandardJavaFileManager standardFileManager = compiler.getStandardFileManager(null, null, null);
    InMemoryFileManager fileManager = new InMemoryFileManager(standardFileManager);

    StringWriter output = new StringWriter();
    List<String> arguments =
        Arrays.asList(
            "-classpath",
            System.getProperty("java.class.path"),
            "-Xplugin:" + DatadogCompilerPlugin.NAME);
    List<InMemorySourceFile> compilationUnits =
        singletonList(new InMemorySourceFile(className, classSource));

    JavaCompiler.CompilationTask task =
        compiler.getTask(output, fileManager, null, arguments, null, compilationUnits);
    task.call();

    return fileManager;
  }
}
