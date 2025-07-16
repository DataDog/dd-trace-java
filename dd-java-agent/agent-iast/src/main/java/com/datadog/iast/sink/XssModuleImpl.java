package com.datadog.iast.sink;

import static com.datadog.iast.taint.Tainteds.canBeTainted;

import com.datadog.iast.Dependencies;
import com.datadog.iast.model.Location;
import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.util.Iterators;
import datadog.trace.api.iast.sink.XssModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class XssModuleImpl extends SinkModuleBase implements XssModule {

  private static final int MAX_LENGTH = 500;

  public XssModuleImpl(final Dependencies dependencies) {
    super(dependencies);
  }

  @Override
  public void onXss(@Nonnull String s) {
    if (!canBeTainted(s)) {
      return;
    }
    checkInjection(VulnerabilityType.XSS, s);
  }

  @Override
  public void onXss(@Nonnull String s, @Nonnull String clazz, @Nonnull String method) {
    if (!canBeTainted(s)) {
      return;
    }
    checkInjection(VulnerabilityType.XSS, s, new ClassMethodLocationSupplier(clazz, method));
  }

  @Override
  public void onXss(@Nonnull char[] array) {
    if (array == null || array.length == 0) {
      return;
    }
    checkInjection(VulnerabilityType.XSS, array);
  }

  @Override
  public void onXss(@Nonnull String format, @Nullable Object[] args) {
    if ((args == null || args.length == 0) && !canBeTainted(format)) {
      return;
    }
    if (args == null || args.length == 0) {
      checkInjection(VulnerabilityType.XSS, format);
    } else {
      checkInjection(VulnerabilityType.XSS, Iterators.of(format, args));
    }
  }

  @Override
  public void onXss(@Nonnull CharSequence s, @Nullable String file, int line) {
    if (!canBeTainted(s) || !canBeTainted(file)) {
      return;
    }
    checkInjection(VulnerabilityType.XSS, s, new FileAndLineLocationSupplier(file, line));
  }

  private static String truncate(final String s) {
    if (s == null || s.length() <= MAX_LENGTH) {
      return s;
    }
    return s.substring(0, MAX_LENGTH);
  }

  private static class ClassMethodLocationSupplier implements LocationSupplier {
    private final String clazz;
    private final String method;

    private ClassMethodLocationSupplier(final String clazz, final String method) {
      this.clazz = clazz;
      this.method = method;
    }

    @Override
    public Location build(final @Nullable AgentSpan span) {
      return Location.forSpanAndClassAndMethod(span, truncate(clazz), truncate(method));
    }
  }

  private static class FileAndLineLocationSupplier implements LocationSupplier {
    private final String file;
    private final int line;

    private FileAndLineLocationSupplier(final String file, final int line) {
      this.file = file;
      this.line = line;
    }

    @Override
    public Location build(@Nullable final AgentSpan span) {
      return Location.forSpanAndFileAndLine(span, truncate(file), line);
    }
  }
}
