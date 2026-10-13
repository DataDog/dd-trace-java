package com.datadog.iast.sink;

import static com.datadog.iast.taint.Tainteds.canBeTainted;

import com.datadog.iast.Dependencies;
import com.datadog.iast.model.VulnerabilityType;
import datadog.trace.api.iast.sink.CodeInjectionModule;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import javax.annotation.Nonnull;

public class CodeInjectionModuleImpl extends SinkModuleBase implements CodeInjectionModule {

  public CodeInjectionModuleImpl(final Dependencies dependencies) {
    super(dependencies);
  }

  @Override
  public void onEval(@Nonnull Reader reader) {
    // IAST propagates taint only to StringReader and InputStreamReader.
    if (!(reader instanceof StringReader) && !(reader instanceof InputStreamReader)) {
      return;
    }
    checkInjection(VulnerabilityType.CODE_INJECTION, reader);
  }

  @Override
  public void onEval(@Nonnull String script) {
    if (!canBeTainted(script)) {
      return;
    }
    checkInjection(VulnerabilityType.CODE_INJECTION, script);
  }
}
