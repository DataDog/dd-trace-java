package datadog.trace.instrumentation.aws.v2;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Groups the instrumentations for AWS SDK 2.2+. */
@AutoService(InstrumenterModule.class)
public final class AwsSdkModule extends InstrumenterModule.Tracing {

  public AwsSdkModule() {
    super("aws-sdk");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.instrumentation.aws.v2.AwsSdkClientDecorator",
      "datadog.trace.instrumentation.aws.v2.TracingExecutionInterceptor"
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse", "java.lang.String");
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return Arrays.asList(new AwsClientInstrumentation(), new AwsHttpClientInstrumentation());
  }
}
