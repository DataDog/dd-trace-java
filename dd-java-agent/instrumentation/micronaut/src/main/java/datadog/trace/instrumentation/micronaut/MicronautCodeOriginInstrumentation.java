package datadog.trace.instrumentation.micronaut;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.instrumentation.codeorigin.CodeOriginInstrumentation;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@AutoService(InstrumenterModule.class)
public class MicronautCodeOriginInstrumentation extends CodeOriginInstrumentation {

  public static final String IO_MICRONAUT_HTTP_ANNOTATION = "io.micronaut.http.annotation.";

  public MicronautCodeOriginInstrumentation() {
    super("micronaut", "micronaut-span-origin");
  }

  @Override
  public String muzzleDirective() {
    return "micronaut-common";
  }

  @Override
  protected Set<String> getAnnotations() {
    return new HashSet<>(
        Arrays.asList(
            IO_MICRONAUT_HTTP_ANNOTATION + "Get",
            IO_MICRONAUT_HTTP_ANNOTATION + "Post",
            IO_MICRONAUT_HTTP_ANNOTATION + "Put",
            IO_MICRONAUT_HTTP_ANNOTATION + "Delete",
            IO_MICRONAUT_HTTP_ANNOTATION + "Patch",
            IO_MICRONAUT_HTTP_ANNOTATION + "Head",
            IO_MICRONAUT_HTTP_ANNOTATION + "Options"));
  }
}
