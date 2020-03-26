package datadog.trace.agent.tooling.bytebuddy.matcher.testclasses;

import datadog.trace.api.Trace;

public class TracedClass extends UntracedClass {
  @Trace
  @Override
  public void g() {}

  @Trace
  @Override
  public void f() {}

  @Trace
  @Override
  public void e() {}

  @Trace
  @Override
  public void d() {}

  @Trace
  @Override
  public void c() {}

  @Trace
  @Override
  public void b() {}

  @Trace
  @Override
  public void a() {}
}
