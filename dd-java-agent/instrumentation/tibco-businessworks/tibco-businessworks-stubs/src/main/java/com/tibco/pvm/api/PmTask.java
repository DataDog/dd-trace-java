package com.tibco.pvm.api;

import com.tibco.pvm.api.session.PmContext;

public interface PmTask extends PmWorkUnit {
  PmTask getParent(PmContext pmContext);
}
