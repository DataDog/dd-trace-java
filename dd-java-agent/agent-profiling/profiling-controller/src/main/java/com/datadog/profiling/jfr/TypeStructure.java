package com.datadog.profiling.jfr;

import java.util.Collections;
import java.util.List;

/** A structure-like holder class for the type's fields and annotations */
final class TypeStructure {
  static final TypeStructure EMPTY =
      new TypeStructure(Collections.emptyList(), Collections.emptyList());

  final List<TypedField> fields;
  final List<Annotation> annotations;

  TypeStructure(List<TypedField> fields, List<Annotation> annotations) {
    this.fields = fields;
    this.annotations = annotations;
  }
}
