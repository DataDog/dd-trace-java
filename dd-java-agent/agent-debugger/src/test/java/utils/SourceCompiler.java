package utils;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

public final class SourceCompiler {
  public enum DebugInfo {
    LINES,
    VARIABLES,
    ALL,
    NONE
  }

  public static Map<String, byte[]> compile(String className, String source, DebugInfo debug) {
    JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
    if (jc == null) throw new RuntimeException("Compiler unavailable");

    JavaSourceFromString jsfs = new JavaSourceFromString(className, source);

    Iterable<? extends JavaFileObject> fileObjects = Collections.singletonList(jsfs);

    List<String> options = new ArrayList<>();
    switch (debug) {
      case ALL:
        {
          options.add("-g");
          break;
        }
      case VARIABLES:
        {
          options.add("-g:vars");
          break;
        }
      case LINES:
        {
          options.add("-g:lines");
          break;
        }
      case NONE:
        {
          options.add("-g:none");
          break;
        }
    }
    options.add("-target");
    options.add("8");
    options.add("-source");
    options.add("8");
    options.add("-cp");
    options.add(System.getProperty("java.class.path"));

    StringWriter output = new StringWriter();
    MemoryJavaFileManager fileManager =
        new MemoryJavaFileManager(jc.getStandardFileManager(null, null, null));
    if (jc.getTask(output, fileManager, null, options, null, fileObjects).call()) {
      return fileManager.getClassBytes();
    } else {
      throw new RuntimeException("Compilation failed :" + output);
    }
  }
}
