package datadog.trace.instrumentation.aws.v0;

import static java.util.Collections.singletonMap;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.Map;

/** Repackaged AWS SDK instrumentations for Amazon EMR. */
@AutoService(InstrumenterModule.class)
public class EmrSdkModule extends AwsSdkModule {
  public EmrSdkModule() {
    super("com.amazon.ws.emr.hadoop.fs.shaded.com.amazonaws", "emr-aws-sdk");
  }

  @Override
  public String muzzleDirective() {
    return "emr-aws-sdk";
  }

  @Override
  public Map<String, String> adviceShading() {
    return singletonMap("com.amazonaws", "com.amazon.ws.emr.hadoop.fs.shaded.com.amazonaws");
  }
}
