/*
 * SPDX-FileCopyrightText: 2026 splinteredlight
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package it.niedermann.nextcloud.deck.forkai.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Successful response of both the assistant run and the auto-tag run:
 * <code>{summary, results: [...], notes: [...]}</code>.
 */
public class AiRunResult {

    @SerializedName("summary")
    @Nullable
    private String summary;

    @SerializedName("results")
    @Nullable
    private List<AiActionResult> results;

    @SerializedName("notes")
    @Nullable
    private List<String> notes;

    public AiRunResult() {
        // Gson
    }

    public AiRunResult(@Nullable String summary, @Nullable List<AiActionResult> results, @Nullable List<String> notes) {
        this.summary = summary;
        this.results = results;
        this.notes = notes;
    }

    @NonNull
    public String getSummary() {
        return summary == null ? "" : summary;
    }

    @NonNull
    public List<AiActionResult> getResults() {
        if (results == null) {
            return Collections.emptyList();
        }
        final var nonNull = new ArrayList<AiActionResult>(results.size());
        for (final var result : results) {
            if (result != null) {
                nonNull.add(result);
            }
        }
        return nonNull;
    }

    @NonNull
    public List<String> getNotes() {
        if (notes == null) {
            return Collections.emptyList();
        }
        final var nonBlank = new ArrayList<String>(notes.size());
        for (final var note : notes) {
            if (note != null && !note.trim().isEmpty()) {
                nonBlank.add(note);
            }
        }
        return nonBlank;
    }

    /**
     * @return a copy of this result with one more note appended (used for client-side remarks).
     */
    @NonNull
    public AiRunResult withNote(@NonNull String note) {
        final var newNotes = new ArrayList<>(getNotes());
        newNotes.add(Objects.requireNonNull(note));
        return new AiRunResult(summary, getResults(), newNotes);
    }
}
