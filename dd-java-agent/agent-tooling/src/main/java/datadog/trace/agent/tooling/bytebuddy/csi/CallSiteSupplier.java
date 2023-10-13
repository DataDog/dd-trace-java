package datadog.trace.agent.tooling.bytebuddy.csi;

import datadog.trace.agent.tooling.csi.CallSites;
import java.util.function.Supplier;

public interface CallSiteSupplier extends Supplier<Iterable<CallSites>> {}
