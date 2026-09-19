/*
 * Copyright 2026 Carl Stainton
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.instanto.sarto.onejar;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PACKAGE;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.CLASS;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Declares the runtime that owns an implementation or package.
 *
 * <p>Unannotated code is portable. The metadata has no CDI dependency and is consumed at build time
 * when Sarto creates target-specific graphs, bindings, and TeaVM reachability indexes.
 *
 * <p>Method and field placements are recorded in the index but cannot be selected as runtime
 * targets and have no effect. Member-level subtraction could be flagged at compile time, but the
 * resulting per-view class shapes are difficult to reason about, so per-runtime members belong in a
 * target-owned class instead. Patching compiled third-party archives (classpath shadowing with
 * explicit opt-in) is a separate build-internal concern, not a developer authoring construct.
 */
@Documented
@Retention(CLASS)
@Target({PACKAGE, TYPE, METHOD, FIELD})
public @interface RuntimeTarget {
  Kind value();

  enum Kind {
    JVM,
    TEAVM
  }
}
