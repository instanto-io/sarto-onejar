/*
 * Copyright 2026 Carl Stainton
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.instanto.sarto.onejar;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.SOURCE;

import java.lang.annotation.Documented;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/** Requests one CDI-independent, target-specific generated factory binding. */
@Documented
@Retention(SOURCE)
@Target(TYPE)
@Repeatable(StaticRuntimeBindings.class)
public @interface StaticRuntimeBinding {
  /** Java method name exposed by the generated target binding class. */
  String name();

  /** Portable contract returned to application assembly code. */
  Class<?> contract();

  /** Target-owned implementation constructed by the generated binding. */
  Class<?> implementation();

  /** Runtime that owns this implementation. */
  RuntimeTarget.Kind target();
}
