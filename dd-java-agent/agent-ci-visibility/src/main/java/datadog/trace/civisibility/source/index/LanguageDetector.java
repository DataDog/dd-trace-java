package datadog.trace.civisibility.source.index;

import datadog.trace.api.civisibility.domain.Language;
import datadog.trace.civisibility.source.Utils;
import java.io.IOException;
import java.lang.annotation.Annotation;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class LanguageDetector {

  private static final Logger log = LoggerFactory.getLogger(LanguageDetector.class);

  @Nullable
  public Language detect(@Nonnull Class<?> clazz) {
    Class<?> c = clazz;
    while (c != null) {
      Class<?>[] interfaces = c.getInterfaces();
      for (Class<?> anInterface : interfaces) {
        String interfaceName = anInterface.getName();
        if ("groovy.lang.GroovyObject".equals(interfaceName)) {
          return Language.GROOVY;
        }
      }

      Annotation[] annotations = c.getAnnotations();
      for (Annotation annotation : annotations) {
        Class<? extends Annotation> annotationType = annotation.annotationType();
        if ("kotlin.Metadata".equals(annotationType.getName())) {
          return Language.KOTLIN;
        }
        if ("scala.reflect.ScalaSignature".equals(annotationType.getName())) {
          return Language.SCALA;
        }
      }

      c = c.getSuperclass();
    }

    try {
      // try to parse the class file to see if it contains filename attribute
      String fileName = Utils.getFileName(clazz);
      if (fileName != null) {
        return Language.getByFileName(fileName);
      }
    } catch (IOException e) {
      log.debug("Error while trying to read filename from class {}", clazz.getName(), e);
    }

    // assuming Java
    return Language.JAVA;
  }
}
