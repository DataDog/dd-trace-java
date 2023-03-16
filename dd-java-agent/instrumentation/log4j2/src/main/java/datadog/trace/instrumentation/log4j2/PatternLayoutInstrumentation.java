/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package datadog.trace.instrumentation.log4j2;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import org.apache.logging.log4j.core.layout.PatternLayout;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.*;

@AutoService(Instrumenter.class)
public class PatternLayoutInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {
  public PatternLayoutInstrumentation() {
    super("log4j", "log4j-2");
  }

  @Override
  protected boolean defaultEnabled() {
    return Config.get().isLogsInjectionEnabled();
  }

  @Override
  public String instrumentedType() {
    return "org.apache.logging.log4j.core.layout.PatternLayout$Builder";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("build"))
            .and(takesArguments(0))
            ,
        packageName + ".PatternLayoutBuildAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[]{
        packageName + ".PatternLayoutBuildAdvice"
    };
  }
}
