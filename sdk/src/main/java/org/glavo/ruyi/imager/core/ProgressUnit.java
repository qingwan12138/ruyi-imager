// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core;

import org.jetbrains.annotations.NotNullByDefault;

/// Unit of the counters carried by a [ProgressEvent].
///
/// Most stages report byte counters, but fastboot reports abstract step counters and some stages
/// only report a completed/not-completed sentinel. Presentation layers need this distinction to
/// decide whether a counter may be formatted as a byte size.
@NotNullByDefault
public enum ProgressUnit {
    /// Counters are byte counts.
    BYTES,

    /// Counters are abstract step counts.
    STEPS,

    /// Counters carry no useful magnitude.
    NONE
}
