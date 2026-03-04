package datadog.trace.agent;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Architecture fitness tests enforcing module dependency rules. These rules prevent the most common
 * violations found during PR reviews: bootstrap depending on core, instrumentations reaching into
 * core internals, and forbidden JDK APIs in bootstrap code.
 *
 * <p>Uses {@link FreezingArchRule} to baseline existing violations so only new violations fail.
 */
class ArchitectureTest {

  private static JavaClasses allClasses;

  @BeforeAll
  static void importClasses() {
    allClasses =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("datadog.trace");
  }

  @Test
  void bootstrapShouldNotDependOnCore() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("datadog.trace.bootstrap..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("datadog.trace.core..")
            .because("Bootstrap classes load before core and must not depend on core internals");

    FreezingArchRule.freeze(rule).check(allClasses);
  }

  @Test
  void instrumentationShouldNotDependOnCoreInternals() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("datadog.trace.instrumentation..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("datadog.trace.core.internal..")
            .because("Instrumentations should use internal-api, not core internals directly");

    FreezingArchRule.freeze(rule).check(allClasses);
  }

  @Test
  void bootstrapShouldNotUseJavaUtilLogging() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("datadog.trace.bootstrap..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("java.util.logging..")
            .because(
                "java.util.logging locks in the log manager before the app configures it"
                    + " (see docs/bootstrap_design_guidelines.md)");

    FreezingArchRule.freeze(rule).check(allClasses);
  }

  @Test
  void bootstrapShouldNotUseJavaxManagement() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("datadog.trace.bootstrap..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("javax.management..")
            .because(
                "javax.management causes class loading issues in premain"
                    + " (see docs/bootstrap_design_guidelines.md)");

    FreezingArchRule.freeze(rule).check(allClasses);
  }
}
