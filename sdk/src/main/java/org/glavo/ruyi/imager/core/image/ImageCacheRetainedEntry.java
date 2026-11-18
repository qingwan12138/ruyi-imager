// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.jetbrains.annotations.NotNullByDefault;

/// One cached image version that a bulk cleanup leaves for explicit selection.
///
/// P3:159 全量清理会跳过只能显式选择的旧目录，但预览仍要显示它的 ATOM 和原因，
/// 否则用户看不到条目，也不知道应该显式清理哪个版本。
///
/// @param entry retained cache entry.
/// @param reason why the entry was retained.
@NotNullByDefault
public record ImageCacheRetainedEntry(ImageCacheEntry entry, Reason reason) {
    /// Why a bulk cleanup leaves an entry behind.
    @NotNullByDefault
    public enum Reason {
        /// The legacy-looking directory could belong to a retired version and needs a full ATOM.
        AMBIGUOUS_LEGACY_DIRECTORY
    }
}
