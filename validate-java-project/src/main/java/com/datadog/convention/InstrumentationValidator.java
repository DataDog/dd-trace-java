package com.datadog.convention;

import static com.datadog.convention.Issue.error;
import static com.datadog.convention.Issue.warning;
import static java.util.Collections.emptyList;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class InstrumentationValidator {
  private static final Pattern INSTRUMENTATION_MODULE_PATTERN = Pattern.compile("^([a-z0-9]+(-[a-z0-9.]+)*/)?[a-z0-9]+(-[a-z0-9.]+)*-[0-9]+(\\.[0-9]+(\\.[0-9]+)?)?$");


  private final List<Issue> issues;
  private final JavaParser javaParser;

  public InstrumentationValidator() {
    this.issues = new ArrayList<>();
    this.javaParser = new JavaParser();
  }

  private void validateFolders(Path instrumentationPath, List<Path> instrumentationFolders) {
    String lastFileName = null;
    for (Path path : instrumentationFolders) {
      String fileName = instrumentationPath.relativize(path).toString();
      if (fileName.endsWith("-common")) {
        continue;
      }
      if (!INSTRUMENTATION_MODULE_PATTERN.matcher(fileName).matches()) {
        this.issues.add(warning(path, "Instrumentation module does not follow naming conventions"));
      } else if (lastFileName != null && !fileName.contains("/")) {
        String lastBaseName = lastFileName.contains("-") ? lastFileName.substring(0, lastFileName.lastIndexOf('-')) : lastFileName;
        String baseName = fileName.substring(0, fileName.lastIndexOf('-'));
        if (baseName.equals(lastBaseName)) {
          this.issues.add(warning(path, "Instrumentations should be regroup in a "+baseName+" folder"));
        }
      }
      lastFileName = fileName;
    }
  }

  private List<Path> listInstrumentationFolders(Path instrumentationPath) {
    try (Stream<Path> stream = Files.walk(instrumentationPath)) {
      return stream.skip(1).filter(this::isGradleModule).sorted().toList();
    } catch (IOException e) {
      this.issues.add(error(instrumentationPath, e.getMessage()));
      return emptyList();
    }
  }

  private boolean isGradleModule(Path path) {
    Path gradleFile = path.resolve("build.gradle");
    return Files.isDirectory(path) && Files.isRegularFile(gradleFile);
  }

  public List<Issue> validate(String baseDirectory) {
    Path instrumentationPath = Path.of(baseDirectory, "dd-java-agent", "instrumentation");
    if (!Files.exists(instrumentationPath)) {
      this.issues.add(error(instrumentationPath, "Instrumentation folder not found"));
      return this.issues;
    }


    List<Path> instrumentationFolders = listInstrumentationFolders(instrumentationPath);
    validateFolders(instrumentationPath, instrumentationFolders);
    for (Path instrumentationFolder : instrumentationFolders) {
      validateSources(instrumentationFolder);
    }


//    try (Stream<Path> stream = Files.walk(instrumentationPath)) {
//      stream.forEach(path -> {
//        if (Files.isDirectory(path)) {
//          String subdirName = path.getFileName().toString();
//          if (!isKebabCase(subdirName)) {
//            issues.add("Subdirectory '" + subdirName + "' is not in kebab-case.");
//          }
//          validateJavaFiles(path.resolve("src/main/java"), issues);
//        }
//      });
//    } catch (IOException e) {
//      issues.add("Error reading instrumentation path: " + e.getMessage());
//    }

    return issues;
  }

  private void validateSources(Path instrumentationFolder) {
    Path mainPath = instrumentationFolder.resolve("src").resolve("main");
    if (!Files.exists(mainPath)) {
      return;
    }
    try (Stream<Path> stream = Files.walk(mainPath)) {
      stream.filter(path -> path.toString().endsWith(".java"))
          .forEach(javaFile -> {
        try {
          CompilationUnit cu = parseJavaFile(javaFile);
          validateInheritance(cu, javaFile.toString());
        } catch (IOException e) {
          this.issues.add(error(javaFile, "Could not parse Java file:" + e.getMessage()));
        }
      });
    } catch (IOException e) {
      this.issues.add(error(instrumentationFolder, "Could not check source files:" + e.getMessage()));
    }
  }

  private void validateInheritance(CompilationUnit cu, String fileName) {
//    cu.accept(new VoidVisitorAdapter<>() {
//      @Override
//      public void visit(ClassOrInterfaceDeclaration n, List<Issue> arg) {
//        super.visit(n, arg);
//        String className = n.getNameAsString();
//
//        if (className.endsWith("Decorator")) {
//          if (n.getExtendedTypes().isEmpty() || !n.getExtendedTypes().get(0).getNameAsString().contains("Decorator")) {
//            arg.add(fileName + ": Class '" + className + "' should extend a class with 'Decorator' in its name.");
//          }
//        } else if (!n.getExtendedTypes().isEmpty() && n.getExtendedTypes().get(0).getNameAsString().contains("Decorator")) {
//          if (!className.endsWith("Decorator")) {
//            arg.add(fileName + ": Class '" + className + "' extends a 'Decorator' class but does not end with 'Decorator'.");
//          }
//        }
//
//        if (className.endsWith("Instrumentation") || className.endsWith("Module")) {
//          if (n.getExtendedTypes().isEmpty() && n.getImplementedTypes().isEmpty()) {
//            arg.add(fileName + ": Class '" + className + "' should extend or implement a class with 'Instrument' in its name.");
//          } else if (n.getExtendedTypes().isEmpty() || n.getImplementedTypes().stream().noneMatch(type -> type.getNameAsString().contains("Instrument"))) {
//            arg.add(fileName + ": Class '" + className + "' should extend a class with 'Instrument' in its name.");
//          }
//        }
//
//        if (className.endsWith("Advice")) {
//          boolean hasAdviceAnnotation = n.getMethods().stream().anyMatch(method -> method.getAnnotations().stream().anyMatch(anno -> anno.getNameAsString().startsWith("Advice")));
//          if (!hasAdviceAnnotation) {
//            arg.add(fileName + ": Class '" + className + "' does not have a method tagged with an @Advice annotation.");
//          }
//        }
//      }
//    }, this.issues);
  }

  private boolean isKebabCase(String name) {
    return INSTRUMENTATION_MODULE_PATTERN.matcher(name).matches();
  }

  private CompilationUnit parseJavaFile(Path filePath) throws IOException {
    String content = Files.readString(filePath);
    ParseResult<CompilationUnit> parse = this.javaParser.parse(content);
    return parse.getResult().orElseThrow(() -> new RuntimeException("Failed to parse file " + filePath));
  }
}
