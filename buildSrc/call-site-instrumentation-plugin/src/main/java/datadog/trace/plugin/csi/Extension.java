package datadog.trace.plugin.csi;

import datadog.trace.plugin.csi.AdviceGenerator.CallSiteResult;
import datadog.trace.plugin.csi.PluginApplication.Configuration;
import datadog.trace.plugin.csi.impl.CallSiteSpecification;
import datadog.trace.plugin.csi.impl.ext.IastExtension;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nonnull;

public interface Extension {

  boolean appliesTo(@Nonnull CallSiteSpecification spec);

  void apply(@Nonnull Configuration configuration, @Nonnull CallSiteResult result) throws Exception;

  List<Extension> EXTENSIONS = Collections.singletonList(new IastExtension());
}
