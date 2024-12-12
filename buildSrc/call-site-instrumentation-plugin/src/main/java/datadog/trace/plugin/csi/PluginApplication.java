package datadog.trace.plugin.csi;

import static datadog.trace.plugin.csi.impl.CallSiteFactory.adviceGenerator;
import static datadog.trace.plugin.csi.impl.CallSiteFactory.specificationBuilder;
import static datadog.trace.plugin.csi.impl.CallSiteFactory.typeResolver;

import datadog.trace.plugin.csi.AdviceGenerator.CallSiteResult;
import datadog.trace.plugin.csi.impl.CallSiteSpecification;
import datadog.trace.plugin.csi.util.CallSiteUtils;
import datadog.trace.plugin.csi.util.ErrorCode;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class PluginApplication {

  public static void main(final String[] args) {
    try {
      final Path parameters = getParameters(args);
      final Configuration configuration = getConfiguration(parameters);
      final List<CallSiteSpecification> specs = searchForCallSites(configuration);
      final List<CallSiteResult> result =
          specs.stream().map(generateAdviceClosure(configuration)).collect(Collectors.toList());
      final boolean failed = result.stream().anyMatch(it -> !it.isSuccess());
      printReport(configuration, result, failed);
      System.exit(failed ? 1 : 0);
    } catch (final RuntimeException e) {
      e.printStackTrace(System.err);
      System.exit(1);
    }
  }

  private static Function<CallSiteSpecification, CallSiteResult> generateAdviceClosure(
      final Configuration configuration) {
    final AdviceGenerator adviceGenerator = getAdviceGenerator(configuration);
    return spec -> {
      final CallSiteResult result = adviceGenerator.generate(spec);
      if (result.isSuccess()) {
        Extension.EXTENSIONS.stream()
            .filter(ext -> ext.appliesTo(spec))
            .forEach(
                ext -> {
                  try {
                    ext.apply(configuration, result);
                  } catch (final Throwable e) {
                    result.addError(e, ErrorCode.EXTENSION_ERROR, ext.getClass());
                  }
                });
      }
      return result;
    };
  }

  private static void printReport(
      final Configuration configuration, final List<CallSiteResult> result, final boolean failed) {
    CallSiteReporter.getReporter(configuration)
        .forEach(reporter -> reporter.report(result, failed));
  }

  private static List<CallSiteSpecification> searchForCallSites(final Configuration configuration) {
    try {
      final SpecificationBuilder builder = specificationBuilder();
      final List<CallSiteSpecification> result = new ArrayList<>();
      final Pattern pattern = Pattern.compile(".*" + configuration.suffix + "\\.class$");
      Files.walkFileTree(
          configuration.classesFolder,
          new SimpleFileVisitor<Path>() {
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) {
              if (Files.isRegularFile(file)
                  && pattern.matcher(file.getFileName().toString()).matches()) {
                builder.build(file.toFile()).ifPresent(result::add);
              }
              return FileVisitResult.CONTINUE;
            }
          });
      return result;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static AdviceGenerator getAdviceGenerator(final Configuration configuration) {
    final URL[] urls =
        configuration.classPath.stream().map(CallSiteUtils::toURL).toArray(URL[]::new);
    final ClassLoader loader = new URLClassLoader(urls);
    final TypeResolver resolver = typeResolver(loader);
    return adviceGenerator(configuration.targetFolder.toFile(), resolver);
  }

  private static Configuration getConfiguration(final Path parameters) {
    try {
      final List<String> lines = Files.readAllLines(parameters);
      final Path projectFolder = Paths.get(lines.get(0));
      final Path classesFolder = Paths.get(lines.get(1));
      final Path targetFolder = Paths.get(lines.get(2));
      final String suffix = lines.get(3).trim();
      final List<String> reporters = Arrays.asList(lines.get(4).trim().split(","));
      final List<Path> classPaths =
          lines.stream().skip(5).map(Paths::get).collect(Collectors.toList());
      return new Configuration(
          projectFolder, classesFolder, targetFolder, classPaths, suffix, reporters);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static Path getParameters(final String[] args) {
    if (args.length != 1) {
      throw new IllegalArgumentException(
          "The application expected a single parameter with the configuration");
    }
    final Path parameters = Paths.get(args[0]);
    if (!Files.exists(parameters)) {
      throw new IllegalArgumentException("File '" + parameters + "' not found%n");
    }
    return parameters;
  }

  public static class Configuration {
    private final Path srcFolder;
    private final Path classesFolder;
    private final Path targetFolder;
    private final List<Path> classPath;
    private final String suffix;
    private final List<String> reporters;

    public Configuration(
        final Path srcFolder,
        final Path classesFolder,
        final Path targetFolder,
        final List<Path> classPath,
        final String suffix,
        final List<String> reporters) {
      this.srcFolder = srcFolder;
      this.classesFolder = classesFolder;
      this.targetFolder = targetFolder;
      this.classPath = classPath;
      this.suffix = suffix;
      this.reporters = reporters;
    }

    public Path getSrcFolder() {
      return srcFolder;
    }

    public Path getClassesFolder() {
      return classesFolder;
    }

    public Path getTargetFolder() {
      return targetFolder;
    }

    public List<Path> getClassPath() {
      return classPath;
    }

    public String getSuffix() {
      return suffix;
    }

    public List<String> getReporters() {
      return reporters;
    }
  }
}
