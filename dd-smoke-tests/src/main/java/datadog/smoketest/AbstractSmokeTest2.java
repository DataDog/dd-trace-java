package datadog.smoketest;

import org.junit.jupiter.api.BeforeAll;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

public class AbstractSmokeTest2 {


  protected static String buildDirectory = System.getProperty("datadog.smoketest.builddir");
  protected static String shadowJarPath = System.getProperty("datadog.smoketest.agent.shadowJar.path");
  protected static Process[] processes;



  protected static int numberOfProcesses() {
    return 1;
  }

  protected static String apiKey() {
    return "01234567890abcdef123456789ABCDEF";
  }

  protected static void beforeProcessStart() {}

  protected static ProcessBuilder createProcessBuilder(int number) {
    if (number == 0) {
      return createProcessBuilder();
    } else {
      throw new AssertionError("Override createProcessBuilder(int number) for multiple processes testing");
    }
  }

  protected static ProcessBuilder createProcessBuilder() {
    throw new AssertionError("Override createProcessBuilder() for single process tests");
  }

  @BeforeAll
  protected static void startProcesses() throws IOException {
    if (AbstractSmokeTest2.buildDirectory == null || AbstractSmokeTest2.shadowJarPath == null) {
      throw new AssertionError("Expected system properties not found. Smoke tests have to be run from Gradle. Please make sure that is the case.");
    }
    assert Files.isDirectory(Paths.get(buildDirectory));
    assert Files.isRegularFile(Paths.get(shadowJarPath));

    beforeProcessStart();

    for (int number = 0; number < numberOfProcesses(); number++) {
      ProcessBuilder processBuilder = createProcessBuilder(number);

      processBuilder.environment().put("JAVA_HOME", System.getProperty("java.home"));
      processBuilder.environment().put("DD_API_KEY", apiKey());

      Process process = processBuilder.start();



    }
//    (0..<).each { idx ->
//        ProcessBuilder processBuilder = createProcessBuilder(idx)
//
//
//      processBuilder.environment().put("JAVA_HOME", System.getProperty("java.home"))
//      processBuilder.environment().put("DD_API_KEY", apiKey())
//
//      processBuilder.redirectErrorStream(true)
//
//      Process p = processBuilder.start()
//      testedProcesses[idx] = p
//
//      outputThreads.captureOutput(p, new File(logFilePaths[idx]))
//    }
//    testedProcess = numberOfProcesses == 1 ? testedProcesses[0] : null
//
//
//    (0..<numberOfProcesses).each { idx ->
//        def curProc = testedProcesses[idx]
//
//      if ( !curProc.isAlive() && curProc.exitValue() != 0 ) {
//        def exitCode = curProc.exitValue()
//        def logFile = logFilePaths[idx]
//
//        throw new RuntimeException("Process exited abormally - exitCode:${exitCode}; logFile=${logFile}")
//      }
//    }
  }

//  public static
}
