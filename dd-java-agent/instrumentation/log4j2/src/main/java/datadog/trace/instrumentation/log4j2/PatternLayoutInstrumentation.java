/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package datadog.trace.instrumentation.log4j2;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.*;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;

@AutoService(InstrumenterModule.class)
public class PatternLayoutInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public PatternLayoutInstrumentation() {
    super("log4j", "log4j-2");
  }

  @Override
  protected boolean defaultEnabled() {
    return Config.get().isLogPatternReplace();
  }

  @Override
  public String instrumentedType() {
    return "org.apache.logging.log4j.core.layout.PatternLayout$Builder";
  }

  @Override
  public void methodAdvice(MethodTransformer transformation) {
    transformation.applyAdvice(
        isMethod().and(isPublic()).and(named("build")).and(takesArguments(0)),
        packageName + ".PatternLayoutBuildAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".PatternLayoutBuildAdvice"};
  }
}
