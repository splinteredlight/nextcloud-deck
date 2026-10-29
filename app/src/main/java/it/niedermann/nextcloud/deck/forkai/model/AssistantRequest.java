/*
 * SPDX-FileCopyrightText: 2026 splinteredlight
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package it.niedermann.nextcloud.deck.forkai.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.annotations.SerializedName;

/**
 * Body of the assistant request.
 */
public class AssistantRequest {

    /**
     * The server rejects blank messages and messages longer than this (counted in characters).
     */
    public static final int MAX_MESSAGE_LENGTH = 20_000;

    @SerializedName("message")
    @NonNull
    private final String message;

    public AssistantRequest(@NonNull String message) {
        this.message = message;
    }

    @NonNull
    public String getMessage() {
        return message;
    }

    /**
     * Mirrors the server-side validation so the user gets immediate feedback.
     * The server trims the message and counts Unicode code points.
     */
    public static boolean isValidMessage(@NonNull CharSequence message) {
        final int length = countCharacters(message);
        return length > 0 && length <= MAX_MESSAGE_LENGTH;
    }

    public static boolean isTooLong(@NonNull CharSequence message) {
        return countCharacters(message) > MAX_MESSAGE_LENGTH;
    }

    /**
     * @return the length the server checks: trimmed, in Unicode code points
     */
    public static int countCharacters(@Nullable CharSequence message) {
        if (message == null) {
            return 0;
        }
        final var trimmed = message.toString().trim();
        return trimmed.codePointCount(0, trimmed.length());
    }
}
