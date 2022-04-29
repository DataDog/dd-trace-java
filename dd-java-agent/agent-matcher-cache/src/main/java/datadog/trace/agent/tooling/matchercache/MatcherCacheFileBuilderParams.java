package datadog.trace.agent.tooling.matchercache;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MatcherCacheFileBuilderParams {
  private static final Logger log = LoggerFactory.getLogger(MatcherCacheFileBuilderParams.class);

  public static void printHelp() {
    System.out.println("Matcher Cache Builder CLI usage: -o output-file -cp class-path");
  }

  public static MatcherCacheFileBuilderParams parseArgs(String... args) {
    int len = args.length;
    MatcherCacheFileBuilderParams params = new MatcherCacheFileBuilderParams();
    int i = 0;
    while (i < len) {
      switch (args[i]) {
        case "-o":
          i += 1;
          if (i < len) {
            params.setOutputCacheDataFile(args[i]);
          } else {
            throw new IllegalArgumentException("Missing an expected output file path after (-o)");
          }
          break;
        case "-cp":
          i += 1;
          if (i < len) {
            params.addClassPath(args[i]);
          } else {
            throw new IllegalArgumentException("Missing an expected class path after -cp");
          }
          break;
        default:
          throw new IllegalArgumentException(args[i]);
      }
      i += 1;
    }
    return params;
  }

  private String outputCacheDataFile;
  // TODO private String outputCacheTextFile;
  private List<String> classPaths;
  private File ddAgentJar;

  public String getOutputCacheDataFile() {
    return outputCacheDataFile;
  }

  public String getOutputCacheTextFile() {
    if (outputCacheDataFile == null) {
      return null;
    }
    return outputCacheDataFile + ".txt";
  }

  public Collection<String> getClassPaths() {
    return classPaths;
  }

  public MatcherCacheFileBuilderParams withDDJavaTracerJar(File ddJavaTracerJar) {
    this.ddAgentJar = ddJavaTracerJar;
    return this;
  }

  public boolean validate() {
    boolean valid = true;
    if (outputCacheDataFile == null) {
      log.error("Mandatory output file path (-o) parameter is missing");
      valid = false;
    } else {
      File output = new File(outputCacheDataFile);
      if (output.exists()) {
        log.warn("File {} already exists and will be replaced", output);
      } else {
        File parentFile = output.getParentFile();
        if (parentFile != null && !parentFile.exists()) {
          log.error("Output folder {} doesn't exist", output.getParent());
          valid = false;
        }
      }
    }
    if (classPaths.isEmpty()) {
      log.warn(
          "The classpath to search for classes is not specified. Only JDK and dd-java-tracer classes will be scanned.");
    } else {
      for (String cp : classPaths) {
        if (!new File(cp).exists()) {
          log.error("Class path {} doesn't exist", cp);
          valid = false;
        }
      }
    }
    return valid;
  }

  public File getDDAgentJar() {
    return ddAgentJar;
  }

  public String getJavaHome() {
    return System.getProperty("java.home");
  }

  private MatcherCacheFileBuilderParams() {
    outputCacheDataFile = null;
    classPaths = new ArrayList<>();
  }

  private void setOutputCacheDataFile(String value) {
    if (outputCacheDataFile != null) {
      throw new IllegalArgumentException("Only one output file path (-o) allowed");
    }
    outputCacheDataFile = value;
  }

  private void addClassPath(String value) {
    classPaths.add(value);
  }
}
