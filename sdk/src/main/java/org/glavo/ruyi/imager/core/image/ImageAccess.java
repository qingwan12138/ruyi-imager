// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.Path;

/// Uses a materialized image.
///
/// Catalog implementations that provide a cache lease hold the version lock throughout this action.
/// The default catalog implementation does not acquire a lease.
///
/// @param <T> action result type.
@NotNullByDefault
@FunctionalInterface
public interface ImageAccess<T> {
    /// Runs one action on a materialized image.
    ///
    /// @param materialized materialized image file or artifact directory.
    /// @return action result.
    /// @throws IOException when the action fails.
    T run(Path materialized) throws IOException;
}
