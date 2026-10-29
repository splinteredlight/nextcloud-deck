/*
 * SPDX-FileCopyrightText: 2026 splinteredlight
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package it.niedermann.nextcloud.deck.forkai;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Loads the JSON fixtures in <code>src/test/resources/forkai</code>. They follow the response shapes of the
 * fork's server endpoints.
 */
final class ForkAiFixtures {

    private ForkAiFixtures() {
        // Util class
    }

    @NonNull
    static String read(@NonNull String name) {
        try (InputStream stream = ForkAiFixtures.class.getClassLoader().getResourceAsStream("forkai/" + name)) {
            return new String(Objects.requireNonNull(stream, "Missing fixture " + name).readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
