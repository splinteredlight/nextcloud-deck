/*
 * SPDX-FileCopyrightText: 2026 splinteredlight
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package it.niedermann.nextcloud.deck.forkai;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import it.niedermann.nextcloud.deck.model.ocs.Capabilities;

/**
 * Remembers the {@link ForkAiCapability} per account.
 * <p>
 * Kept in its own {@link SharedPreferences} file instead of a new column on the Room <code>Account</code>
 * entity, so the fork never needs a database migration that could collide with upstream's.
 * The capabilities request uses an ETag, so the value is only refreshed when the server's capabilities change.
 */
public class ForkAiCapabilityStore {

    private static final String PREFS_NAME = "fork_ai_capability";
    private static final String KEY_VERSION_PREFIX = "version_";
    private static final String KEY_CONFIGURED_PREFIX = "configured_";

    private ForkAiCapabilityStore() {
        // Util class
    }

    /**
     * Stores the AI capability of a freshly fetched (HTTP 200) capabilities response.
     * Responses in maintenance mode carry no capabilities and are ignored.
     */
    public static void save(@NonNull Context context, long accountId, @Nullable Capabilities capabilities) {
        if (capabilities == null || capabilities.isMaintenanceEnabled()) {
            return;
        }
        final var capability = capabilities.getForkAi();
        final var editor = getPrefs(context).edit();
        if (capability == null) {
            editor.remove(KEY_VERSION_PREFIX + accountId)
                    .remove(KEY_CONFIGURED_PREFIX + accountId);
        } else {
            editor.putInt(KEY_VERSION_PREFIX + accountId, capability.getVersion())
                    .putBoolean(KEY_CONFIGURED_PREFIX + accountId, capability.isConfigured());
        }
        editor.apply();
    }

    /**
     * @return <code>null</code> if the account's server does not offer the AI endpoints (or was not checked yet)
     */
    @Nullable
    public static ForkAiCapability get(@NonNull Context context, long accountId) {
        final var prefs = getPrefs(context);
        final int version = prefs.getInt(KEY_VERSION_PREFIX + accountId, -1);
        if (version < ForkAiCapability.MIN_VERSION) {
            return null;
        }
        return new ForkAiCapability(version, prefs.getBoolean(KEY_CONFIGURED_PREFIX + accountId, false));
    }

    private static SharedPreferences getPrefs(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
