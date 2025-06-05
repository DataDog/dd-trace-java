package datadog.smoketest.apmtracingdisabled

import datadog.smoketest.AbstractServerSmokeTest
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.test.agent.decoder.DecodedTrace

abstract class AbstractApmTracingDisabledSmokeTest extends AbstractServerSmokeTest {

  @Override
  File createTemporaryFile(int processIndex) {
    return null
  }

  @Override
  String logLevel() {
    return 'debug'
  }

  @Override
  Closure decodedTracesCallback() {
    return {} // force traces decoding
  }

  protected ProcessBuilder createProcess(String[] properties){
    createProcess(-1, properties)
  }


  protected ProcessBuilder createProcess(int processIndex, String[] properties){
    def port = processIndex == -1 ? httpPort : httpPorts[processIndex]
    String springBootShadowJar = System.getProperty("datadog.smoketest.springboot.shadowJar.path")
    List<String> command = []
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll(properties)
    command.addAll((String[]) ['-jar', springBootShadowJar, "--server.port=${port}"])
    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
    // Spring will print all environment variables to the log, which may pollute it and affect log assertions.
    processBuilder.environment().clear()
    return processBuilder
  }

  protected DecodedTrace getServiceTrace(String serviceName) {
    return traces.find { trace ->
      trace.spans.find { span ->
        span.service == serviceName
      }
    }
  }

  protected DecodedTrace getServiceTraceFromUrl(String url) {
    return traces.find { trace ->
      trace.spans.find { span ->
        span.meta["http.url"] == url
      }
    }
  }

  protected checkRootSpanPrioritySampling(DecodedTrace trace, byte priority) {
    return trace.spans[0].metrics['_sampling_priority_v1'] == priority
  }

  protected isSampledBySampler(DecodedTrace trace) {
    def samplingPriority = trace.spans[0].metrics['_sampling_priority_v1']
    return samplingPriority == PrioritySampling.SAMPLER_KEEP || samplingPriority == PrioritySampling.SAMPLER_DROP
  }

  protected hasAppsecPropagationTag(DecodedTrace trace) {
    return trace.spans[0].meta['_dd.p.ts'] == "02"
  }

  protected hasApmDisabledTag(DecodedTrace trace) {
    return trace.spans[0].metrics['_dd.apm.enabled'] == 0
  }

  protected hasASMEvents(DecodedTrace trace){
    return trace.spans[0].meta['_dd.iast.json'] != null || trace.spans[0].meta['_dd.appsec.json'] != null
  }
}
