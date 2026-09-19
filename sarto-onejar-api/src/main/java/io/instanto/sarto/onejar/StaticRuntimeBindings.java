/*
 * Copyright 2026 Carl Stainton
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.instanto.sarto.onejar;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.SOURCE;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/** Container for repeatable {@link StaticRuntimeBinding} declarations. */
@Documented
@Retention(SOURCE)
@Target(TYPE)
public @interface StaticRuntimeBindings {
  StaticRuntimeBinding[] value();
}
