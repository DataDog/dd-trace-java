package datadog.trace.instrumentation.commons.fileupload;

import static java.util.Arrays.asList;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.List;

@AutoService(InstrumenterModule.class)
public class CommonsFileUploadModule extends InstrumenterModule.Iast {
  public CommonsFileUploadModule() {
    super("commons-fileupload");
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return asList(
        new CommonsFileuploadInstrumentation(),
        new FileItemInstrumentation(),
        new FileItemIteratorInstrumentation(),
        new FileItemStreamInstrumentation(),
        new ServletFileUploadInstrumentation());
  }
}
