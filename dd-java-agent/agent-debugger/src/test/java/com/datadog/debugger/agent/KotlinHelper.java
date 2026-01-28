package com.datadog.debugger.agent;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.jetbrains.kotlin.cli.common.ExitCode;
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments;
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer;
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector;
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler;
import org.jetbrains.kotlin.config.Services;

public class KotlinHelper {
  public static Class<?> compileAndLoad(
      String className, String sourceFileName, List<File> outputFilesToDelete) {
    K2JVMCompiler compiler = new K2JVMCompiler();
    K2JVMCompilerArguments args = compiler.createArguments();
    args.setFreeArgs(Collections.singletonList(sourceFileName));
    String compilerOutputDir = "/tmp/" + CapturedSnapshotTest.class.getSimpleName() + "-kotlin";
    args.setDestination(compilerOutputDir);
    args.setClasspath(System.getProperty("java.class.path"));
    ExitCode exitCode =
        compiler.exec(
            new PrintingMessageCollector(System.out, MessageRenderer.WITHOUT_PATHS, true),
            Services.EMPTY,
            args);

    if (exitCode.getCode() != 0) {
      throw new RuntimeException("Kotlin compilation failed");
    }
    File compileOutputDirFile = new File(compilerOutputDir);
    try {
      URLClassLoader urlClassLoader =
          new URLClassLoader(new URL[] {compileOutputDirFile.toURI().toURL()});
      return urlClassLoader.loadClass(className);
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    } finally {
      registerFilesToDeleteDir(compileOutputDirFile, outputFilesToDelete);
    }
  }

  public static void registerFilesToDeleteDir(File dir, List<File> outputFilesToDelete) {
    if (!dir.exists()) {
      return;
    }
    try {
      Files.walk(dir.toPath())
          .sorted(Comparator.reverseOrder())
          .map(Path::toFile)
          .forEach(outputFilesToDelete::add);
    } catch (IOException ex) {
      ex.printStackTrace();
    }
  }
}
