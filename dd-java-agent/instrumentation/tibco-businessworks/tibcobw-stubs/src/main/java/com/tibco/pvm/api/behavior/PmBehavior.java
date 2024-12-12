package com.tibco.pvm.api.behavior;

import com.tibco.pvm.api.PmWorkUnit;
import com.tibco.pvm.api.session.PmContext;

public interface PmBehavior {
  boolean isFinished(PmContext pmContext, PmWorkUnit wu);
}
