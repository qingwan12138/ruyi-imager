// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Tests the SDK diagnostic defaults used without an application message resolver.
@NotNullByDefault
public final class SdkMessagesTest {
    /// Resume progress keeps a readable default message instead of falling back to the key.
    @Test
    public void providesDefaultResumeMessage() {
        SdkMessages.setResolver(null);
        assertEquals(
                "Resuming image.raw from 42%",
                SdkMessages.get("core.download.resuming", "image.raw", 42L));
    }

    /// Every download message used by the downloader has a default pattern.
    @Test
    public void providesDefaultDownloadMessages() {
        SdkMessages.setResolver(null);
        assertEquals("Downloading image.raw", SdkMessages.get("core.download.downloading", "image.raw"));
        assertEquals(
                "Connecting to download source: image.raw",
                SdkMessages.get("core.download.connecting", "image.raw"));
        assertEquals("Downloaded image", SdkMessages.get("core.download.imageComplete", "image"));
    }
}
