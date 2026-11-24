package datadog.trace.agent.tooling.bytebuddy.outline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.agent.tooling.bytebuddy.ClassFileLocators;
import java.io.IOException;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import org.tabletest.junit.TableTest;

class OutlineTypeParserTest {

  // isAnonymousType is no longer supported in outlines for performance reasons,
  // so anonymous classes are only listed to check their other modifiers
  @TableTest({
    "scenario                  | className                                                | isInterface | isAbstract | isAnnotation | isEnum",
    "outer class               | 'datadog.trace.agent.test.EnclosedClasses'               | false       | false      | false        | false ",
    "inner class               | 'datadog.trace.agent.test.EnclosedClasses$Inner'         | false       | false      | false        | false ",
    "static inner class        | 'datadog.trace.agent.test.EnclosedClasses$InnerStatic'   | false       | false      | false        | false ",
    "first anonymous class     | 'datadog.trace.agent.test.EnclosedClasses$1'             | false       | false      | false        | false ",
    "second anonymous class    | 'datadog.trace.agent.test.EnclosedClasses$2'             | false       | false      | false        | false ",
    "anonymous in inner class  | 'datadog.trace.agent.test.EnclosedClasses$Inner$1'       | false       | false      | false        | false ",
    "anonymous in static inner | 'datadog.trace.agent.test.EnclosedClasses$InnerStatic$1' | false       | false      | false        | false ",
    "interface                 | 'datadog.trace.agent.test.EnclosedClasses$Interface'     | true        | true       | false        | false ",
    "abstract class            | 'datadog.trace.agent.test.EnclosedClasses$Abstract'      | false       | true       | false        | false ",
    "annotation                | 'datadog.trace.agent.test.EnclosedClasses$Annotation'    | true        | true       | true         | false ",
    "enum                      | 'datadog.trace.agent.test.EnclosedClasses$Enum'          | false       | false      | false        | true  "
  })
  void testModifiersAreCorrect(
      String className,
      boolean isInterface,
      boolean isAbstract,
      boolean isAnnotation,
      boolean isEnum)
      throws IOException {
    // setup
    OutlineTypeParser parser = new OutlineTypeParser();
    ClassFileLocator locator =
        ClassFileLocators.classFileLocator(Thread.currentThread().getContextClassLoader());
    byte[] bytes = locator.locate(className).resolve();

    // when
    TypeDescription outline = parser.parse(bytes);

    // then
    assertEquals(isInterface, outline.isInterface());
    assertEquals(isAbstract, outline.isAbstract());
    assertEquals(isAnnotation, outline.isAnnotation());
    assertEquals(isEnum, outline.isEnum());
  }
}
