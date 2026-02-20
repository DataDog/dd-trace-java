package datadog.trace.plugin.csi.impl;

import static datadog.trace.plugin.csi.impl.CallSiteFactory.pointcutParser;
import static datadog.trace.plugin.csi.impl.CallSiteFactory.specificationBuilder;
import static datadog.trace.plugin.csi.impl.CallSiteFactory.typeResolver;
import static datadog.trace.plugin.csi.util.CallSiteConstants.TYPE_RESOLVER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.plugin.csi.HasErrors;
import datadog.trace.plugin.csi.ValidationContext;
import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public abstract class BaseCsiPluginTest {

  protected static void assertNoErrors(HasErrors hasErrors) {
    List<String> errors =
        hasErrors.getErrors().stream()
            .map(
                error -> {
                  String causeString = error.getCause() == null ? "-" : error.getCauseString();
                  return error.getMessage() + ": " + causeString;
                })
            .collect(Collectors.toList());
    assertEquals(Collections.emptyList(), errors);
  }

  protected static File fetchClass(Class<?> clazz) {
    try {
      Path folder = Paths.get(clazz.getResource("/").toURI()).resolve("../../");
      String fileSeparator = File.separator.equals("\\") ? "\\\\" : File.separator;
      String classFile = clazz.getName().replaceAll("\\.", fileSeparator) + ".class";
      Path groovy = folder.resolve("groovy/test").resolve(classFile);
      if (Files.exists(groovy)) {
        return groovy.toFile();
      }
      return folder.resolve("java/test").resolve(classFile).toFile();
    } catch (URISyntaxException e) {
      throw new RuntimeException(e);
    }
  }

  protected static CallSiteSpecification buildClassSpecification(Class<?> clazz) {
    File classFile = fetchClass(clazz);
    CallSiteSpecification spec = specificationBuilder().build(classFile).get();
    spec.getAdvices().forEach(advice -> advice.parseSignature(pointcutParser()));
    return spec;
  }

  protected ValidationContext mockValidationContext() {
    ValidationContext context = mock(ValidationContext.class);
    when(context.getContextProperty(TYPE_RESOLVER)).thenReturn(typeResolver());
    return context;
  }
}
