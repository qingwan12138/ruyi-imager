// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.glavo.ruyi.imager.core.ProgressReporter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/// Reads image metadata and downloads image artifacts.
@NotNullByDefault
public interface ImageCatalogService {
    /// Lists images from the local metadata cache.
    ///
    /// @return image catalog snapshot.
    /// @throws IOException when local metadata cannot be read.
    ImageCatalog listImages() throws IOException;

    /// Lists every declared image without cross-repository de-duplication.
    ///
    /// P2:1113 缓存维护基于仓库识别归档声明，而 [#listImages] 会跨仓库按 ATOM 去重；
    /// 两个仓库声明同一 ATOM 时，低优先级仓库的清单会从去重结果中消失。
    ///
    /// @return every declared image, duplicates across repositories included.
    /// @throws IOException when local metadata cannot be read.
    default @Unmodifiable List<ImageEntry> declaredImages() throws IOException {
        return listImages().images();
    }

    /// Invalidates any in-memory metadata cache maintained by this service.
    default void invalidateCache() {
    }

    /// Finds one image by atom name.
    ///
    /// @param atom image atom name.
    /// @return matching image, or null when not found.
    /// @throws IOException when local metadata cannot be read.
    default @Nullable ImageEntry findImage(String atom) throws IOException {
        return RuyiImageSelector.find(listImages(), atom);
    }

    /// Returns a lightweight cache status for one image.
    ///
    /// @param image image to inspect.
    /// @return image cache status.
    /// @throws IOException when the cache cannot be inspected.
    default ImageCacheStatus cacheStatus(ImageEntry image) throws IOException {
        return ImageCacheStatus.unknown(image.distfiles().size());
    }

    /// Downloads an image artifact.
    ///
    /// @param image image to download.
    /// @param reporter progress reporter.
    /// @return local image path.
    /// @throws IOException when download or validation fails.
    Path downloadImage(ImageEntry image, ProgressReporter reporter) throws IOException;

    /// Downloads an image and runs an action on the result.
    ///
    /// Implementations that share a cleanable cache must override this method and hold the version
    /// lock from the start of the download until the action returns. The default implementation does
    /// not acquire a cache lease.
    ///
    /// @param image image to download.
    /// @param reporter progress reporter.
    /// @param action action receiving the materialized path.
    /// @param <T> action result type.
    /// @return action result.
    /// @throws IOException when the download, the materialization or the action fails.
    default <T> T withDownloadedImage(
            ImageEntry image,
            ProgressReporter reporter,
            ImageAccess<T> action) throws IOException {
        return action.run(downloadImage(image, reporter));
    }

    /// Returns whether [#withDownloadedImage] keeps the cache version locked.
    ///
    /// @return whether materialized images are protected by a cache lock.
    default boolean supportsCacheLease() {
        return false;
    }

    /// Returns the root of this catalog's shared image cache, when it uses Ruyi Imager's layout.
    ///
    /// The [org.glavo.ruyi.imager.core.flash.LocalFlashService] uses this root to find the version
    /// lock for local images located in the cache's downloads or artifacts tree.
    ///
    /// @return shared cache root, or null when no compatible cache is known.
    default @Nullable Path cacheDirectory() {
        return null;
    }

    /// Imports matching local distfiles and prepares a complete image without accessing a device.
    /// @param image explicitly selected catalog candidate.
    /// @param sources user-selected files; each must match at least one distfile.
    /// @param allowDownload whether missing files may be fetched from the network.
    /// @param reporter import and preparation progress.
    /// @return missing or invalid cached declarations, or an empty list after successful preparation.
    /// @throws IOException when import, verification, or preparation fails.
    default @Unmodifiable List<RuyiDistfile> prepareLocalImage(
            ImageEntry image, List<Path> sources, boolean allowDownload, ProgressReporter reporter) throws IOException {
        throw new IOException("Local image import is not supported by this catalog service.");
    }
}
