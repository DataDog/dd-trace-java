package datadog.trace.instrumentation.springboot;

import static java.util.Arrays.asList;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.instrumentation.codeorigin.CodeOriginInstrumentation;
import java.util.HashSet;
import java.util.Set;

@AutoService(InstrumenterModule.class)
public class SBCodeOriginInstrumentation extends CodeOriginInstrumentation {
  private static final String WEB_BIND_ANNOTATION = "org.springframework.web.bind.annotation.";

  public SBCodeOriginInstrumentation() {
    super("spring-boot-span-origin");
  }

  @Override
  protected Set<String> getAnnotations() {
    return new HashSet<>(
        asList(
            WEB_BIND_ANNOTATION + "GetMapping",
            WEB_BIND_ANNOTATION + "PostMapping",
            WEB_BIND_ANNOTATION + "PutMapping",
            WEB_BIND_ANNOTATION + "PatchMapping"));
  }
}
