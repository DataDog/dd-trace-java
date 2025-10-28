package com.tibco.pvm.api;

import com.tibco.pvm.api.session.PmContext;
import com.tibco.pvm.api.util.attr.PmAttrFilter;
import com.tibco.pvm.api.util.attr.PmAttribute;
import java.util.List;

public interface PmAttributed {
  Object getAttributeValue(PmContext pmContext, String str);

  List<PmAttribute> getAttributes(PmContext pmContext, PmAttrFilter pmAttrFilter);
}
