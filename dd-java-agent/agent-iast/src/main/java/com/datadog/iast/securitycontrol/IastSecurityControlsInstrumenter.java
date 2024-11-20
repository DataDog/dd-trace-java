package com.datadog.iast.securitycontrol;

import datadog.trace.api.Config;
import datadog.trace.api.iast.securitycontrol.SecurityControl;
import datadog.trace.api.iast.securitycontrol.SecurityControlFormatter;
import org.slf4j.Logger;

import java.lang.instrument.Instrumentation;
import java.util.List;

public class IastSecurityControlsInstrumenter {

  private static final Logger log = org.slf4j.LoggerFactory.getLogger(IastSecurityControlsInstrumenter.class);

  public static void start(Instrumentation inst) {

    List<SecurityControl> securityControls = SecurityControlFormatter.format(Config.get().getIastSecurityControlsConfiguration());
    if (securityControls == null) {
      log.warn("No security controls to apply");
      return;
    }
    for (SecurityControl securityControl : securityControls) {
       inst.addTransformer(new IastSecurityControlTransformer(securityControl), true);
    }

  }

}
