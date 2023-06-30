package datadog.trace.agent.tooling.bytebuddy.csi;

import datadog.trace.api.iast.csi.HasDynamicSupport;
import java.util.function.Function;

public interface DynamicHelperSupplier
    extends Function<ClassLoader, Iterable<Class<? extends HasDynamicSupport>>> {}
