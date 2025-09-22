package com.tibco.pvm.api.util.attr;

import java.util.List;

public interface PmAttrFilter {
  List<PmAttribute> filter(List<PmAttribute> list);
}
