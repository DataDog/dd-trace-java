package datadog.trace.agent.tooling.bytebuddy.csi;

import datadog.trace.agent.tooling.csi.CallSiteAdvice;
import java.util.function.Supplier;

public interface CallSiteSupplier extends Supplier<Iterable<CallSiteAdvice>> {}
