/*
 * SPDX-FileCopyrightText: 2026 splinteredlight
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package it.niedermann.nextcloud.deck.forkai;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Objects;

/**
 * The forked server advertises its AI endpoints as
 * <code>ocs.data.capabilities.deck.forkAi = {version: int, configured: bool}</code>.
 * Stock Deck servers don't have the key, which means the features must stay hidden.
 */
public class ForkAiCapability {

    public static final String KEY = "forkAi";

    /**
     * Lowest contract version this client understands.
     */
    public static final int MIN_VERSION = 1;

    private final int version;
    private final boolean configured;

    public ForkAiCapability(int version, boolean configured) {
        this.version = version;
        this.configured = configured;
    }

    public int getVersion() {
        return version;
    }

    /**
     * @return <code>false</code> if the server has the endpoints but the AI backend is not set up yet.
     */
    public boolean isConfigured() {
        return configured;
    }

    /**
     * @param deck the <code>capabilities.deck</code> object of the capabilities response
     * @return <code>null</code> if the server does not offer the fork's AI endpoints
     */
    @Nullable
    public static ForkAiCapability parse(@Nullable JsonObject deck) {
        if (deck == null || !deck.has(KEY)) {
            return null;
        }
        final JsonElement element = deck.get(KEY);
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        final var forkAi = element.getAsJsonObject();
        final int version = getInt(forkAi.get("version"));
        if (version < MIN_VERSION) {
            return null;
        }
        return new ForkAiCapability(version, getBoolean(forkAi.get("configured")));
    }

    private static int getInt(@Nullable JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return -1;
        }
        try {
            return element.getAsInt();
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Only a real JSON <code>true</code> counts; anything else is treated as "not configured".
     */
    private static boolean getBoolean(@Nullable JsonElement element) {
        return element != null
                && element.isJsonPrimitive()
                && element.getAsJsonPrimitive().isBoolean()
                && element.getAsBoolean();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final var that = (ForkAiCapability) o;
        return version == that.version && configured == that.configured;
    }

    @Override
    public int hashCode() {
        return Objects.hash(version, configured);
    }

    @NonNull
    @Override
    public String toString() {
        return "ForkAiCapability{version=" + version + ", configured=" + configured + '}';
    }
}
