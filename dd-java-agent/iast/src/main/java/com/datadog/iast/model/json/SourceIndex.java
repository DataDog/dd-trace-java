package com.datadog.iast.model.json;

import com.squareup.moshi.JsonQualifier;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
@JsonQualifier
// @Target({ElementType.FIELD, ElementType.PARAMETER})
public @interface SourceIndex {}
