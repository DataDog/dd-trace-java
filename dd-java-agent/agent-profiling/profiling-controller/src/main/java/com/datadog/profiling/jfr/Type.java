package com.datadog.profiling.jfr;

import java.util.List;
import java.util.function.Consumer;

/** A JFR type */
public interface Type extends NamedType {
  /** @return unique type ID */
  long getId();

  /** @return is the type built-in or a custom type */
  boolean isBuiltin();

  /**
   * A simple type has only one field which is of a built-in type
   *
   * @return {@literal true} if the type is 'simple'
   */
  boolean isSimple();

  boolean isResolved();

  /** @return is the type using constant pool */
  boolean hasConstantPool();

  /** @return the super type - may be {@literal null} */
  String getSupertype();

  /** @return the type field structure */
  List<TypedField> getFields();

  TypedField getField(String name);

  List<Annotation> getAnnotations();

  /**
   * Checks whether the type can accept the given value
   *
   * @param value the value to check
   * @return {@literal true} only if the type can safely hold the value
   * @throws IllegalArgumentException if the type can not accept the given value
   */
  boolean canAccept(Object value);

  /**
   * Shortcut for wrapping the given value instance as a {@linkplain TypedValue} object
   *
   * @param value the value to wrap
   * @return a {@linkplain TypedValue} object representing the typed value
   * @throws IllegalArgumentException if the type can not accept the given value
   */
  TypedValue asValue(String value);

  /**
   * Shortcut for wrapping the given value instance as a {@linkplain TypedValue} object
   *
   * @param value the value to wrap
   * @return a {@linkplain TypedValue} object representing the typed value
   * @throws IllegalArgumentException if the type can not accept the given value
   */
  TypedValue asValue(byte value);

  /**
   * Shortcut for wrapping the given value instance as a {@linkplain TypedValue} object
   *
   * @param value the value to wrap
   * @return a {@linkplain TypedValue} object representing the typed value
   * @throws IllegalArgumentException if the type can not accept the given value
   */
  TypedValue asValue(char value);

  /**
   * Shortcut for wrapping the given value instance as a {@linkplain TypedValue} object
   *
   * @param value the value to wrap
   * @return a {@linkplain TypedValue} object representing the typed value
   * @throws IllegalArgumentException if the type can not accept the given value
   */
  TypedValue asValue(short value);

  /**
   * Shortcut for wrapping the given value instance as a {@linkplain TypedValue} object
   *
   * @param value the value to wrap
   * @return a {@linkplain TypedValue} object representing the typed value
   * @throws IllegalArgumentException if the type can not accept the given value
   */
  TypedValue asValue(int value);

  /**
   * Shortcut for wrapping the given value instance as a {@linkplain TypedValue} object
   *
   * @param value the value to wrap
   * @return a {@linkplain TypedValue} object representing the typed value
   * @throws IllegalArgumentException if the type can not accept the given value
   */
  TypedValue asValue(long value);

  /**
   * Shortcut for wrapping the given value instance as a {@linkplain TypedValue} object
   *
   * @param value the value to wrap
   * @return a {@linkplain TypedValue} object representing the typed value
   * @throws IllegalArgumentException if the type can not accept the given value
   */
  TypedValue asValue(float value);

  /**
   * Shortcut for wrapping the given value instance as a {@linkplain TypedValue} object
   *
   * @param value the value to wrap
   * @return a {@linkplain TypedValue} object representing the typed value
   * @throws IllegalArgumentException if the type can not accept the given value
   */
  TypedValue asValue(double value);

  /**
   * Shortcut for wrapping the given value instance as a {@linkplain TypedValue} object
   *
   * @param value the value to wrap
   * @return a {@linkplain TypedValue} object representing the typed value
   * @throws IllegalArgumentException if the type can not accept the given value
   */
  TypedValue asValue(boolean value);

  /**
   * Shortcut for creating a new {@linkplain TypedValue} object for this type
   *
   * @param builderCallback will be called when the new {@linkplain TypedValue} is being initialized
   * @return a {@linkplain TypedValue} object representing the typed value
   * @throws IllegalArgumentException if the type can not be used with builder callback
   */
  TypedValue asValue(Consumer<TypeValueBuilder> builderCallback);

  /**
   * @return a specific {@linkplain TypedValue} instance designated as the {@linkplain null} value
   *     for this type
   */
  TypedValue nullValue();

  /** @return the associated {@linkplain ConstantPool} instance */
  ConstantPool getConstantPool();

  Metadata getMetadata();

  boolean isUsedBy(Type other);
}
