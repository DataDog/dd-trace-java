package com.datadog.debugger.symbol;

import com.datadog.debugger.agent.Generated;
import com.squareup.moshi.Json;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import datadog.trace.util.HashingUtils;

public class LanguageSpecifics {
  @Json(name = "access_modifiers")
  private final List<String> accessModifiers;

  private final List<String> annotations;

  @Json(name = "super_class")
  private final String superClass;

  private final List<String> interfaces;

  @Json(name = "return_type")
  private final String returnType;

  public LanguageSpecifics(
      List<String> accessModifiers,
      List<String> annotations,
      String superClass,
      List<String> interfaces,
      String returnType) {
    this.accessModifiers = accessModifiers;
    this.annotations = annotations;
    this.superClass = superClass;
    this.interfaces = interfaces;
    this.returnType = returnType;
  }

  public List<String> getAccessModifiers() {
    return accessModifiers;
  }

  public List<String> getAnnotations() {
    return annotations;
  }

  public String getSuperClass() {
    return superClass;
  }

  public List<String> getInterfaces() {
    return interfaces;
  }

  public String getReturnType() {
    return returnType;
  }

  @Generated
  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    LanguageSpecifics that = (LanguageSpecifics) o;
    return Objects.equals(accessModifiers, that.accessModifiers)
        && Objects.equals(annotations, that.annotations)
        && Objects.equals(superClass, that.superClass)
        && Objects.equals(interfaces, that.interfaces)
        && Objects.equals(returnType, that.returnType);
  }

  @Generated
  @Override
  public int hashCode() {
    return HashingUtils.hash(accessModifiers, annotations, superClass, interfaces, returnType);
  }

  @Generated
  @Override
  public String toString() {
    return "LanguageSpecifics{"
        + "accessModifiers="
        + accessModifiers
        + ", annotations="
        + annotations
        + ", superClass='"
        + superClass
        + '\''
        + ", interfaces="
        + interfaces
        + ", returnType='"
        + returnType
        + '\''
        + '}';
  }

  public static class Builder {
    private List<String> accessModifiers;
    private List<String> annotations;
    private String superClass;
    private List<String> interfaces;
    private String returnType;

    public Builder addModifiers(Collection<String> modifiers) {
      if (modifiers == null || modifiers.isEmpty()) {
        this.accessModifiers = null;
        return this;
      }
      accessModifiers = new ArrayList<>(modifiers);
      return this;
    }

    public Builder addAnnotations(Collection<String> annotations) {
      if (annotations == null || annotations.isEmpty()) {
        this.annotations = null;
        return this;
      }
      this.annotations = new ArrayList<>(annotations);
      return this;
    }

    public Builder superClass(String superClass) {
      this.superClass = superClass;
      return this;
    }

    public Builder addInterfaces(Collection<String> interfaces) {
      if (interfaces == null || interfaces.isEmpty()) {
        this.interfaces = null;
        return this;
      }
      this.interfaces = new ArrayList<>(interfaces);
      return this;
    }

    public Builder returnType(String returnType) {
      this.returnType = returnType;
      return this;
    }

    public LanguageSpecifics build() {
      return new LanguageSpecifics(
          accessModifiers, annotations, superClass, interfaces, returnType);
    }
  }
}
