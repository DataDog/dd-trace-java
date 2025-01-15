package datadog.context;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestInstancePostProcessor;

public class GetSourceFileInfoExtension implements TestInstancePostProcessor {
  private static Map<String, String> sourceFiles = new HashMap<String, String>();

  @Override
  public void postProcessTestInstance(Object testInstance, ExtensionContext context)
      throws IOException {
    getTestData(context);
  }

  public static Map<String, String> getSourceFiles() {
    return sourceFiles;
  }

  private static void getTestData(ExtensionContext context) throws IOException {
    // get test classname and source file
    String testClassName = context.getTestClass().get().getName();
    String testClassPath = testClassName.replace(".", "/") + ".java";
    String root = Paths.get("").toAbsolutePath().toString();
    String absolutePath = root + "/src/test/java/" + testClassPath;
    String subPath =
        absolutePath.substring(absolutePath.indexOf("dd-trace-java") + "dd-trace-java".length());

    // print to sourceFiles.xml only if source file has not already been added
    if (!sourceFiles.containsKey(testClassName)) {
      File sourceFile = new File(root + "/build/test-results/sourceFiles.xml");
      if (!sourceFile.exists()) {
        sourceFile.createNewFile();
      }
      BufferedWriter writer = new BufferedWriter(new FileWriter(sourceFile, true));
      writer.write(testClassName + ":" + subPath);
      writer.newLine();
      writer.close();
    }
    sourceFiles.put(testClassName, subPath);
  }
}
