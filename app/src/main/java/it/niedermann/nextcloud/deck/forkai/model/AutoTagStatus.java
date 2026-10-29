/*
 * SPDX-FileCopyrightText: 2026 splinteredlight
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package it.niedermann.nextcloud.deck.forkai.model;

import com.google.gson.annotations.SerializedName;

/**
 * Auto-tag status: how many current (non-archived) cards have no label, and how many labels the board has.
 */
public class AutoTagStatus {

    @SerializedName("untagged")
    private int untagged;

    @SerializedName("labels")
    private int labels;

    public AutoTagStatus() {
        // Gson
    }

    public AutoTagStatus(int untagged, int labels) {
        this.untagged = untagged;
        this.labels = labels;
    }

    public int getUntagged() {
        return untagged;
    }

    public int getLabels() {
        return labels;
    }

    /**
     * Same rule as the web dialog: there must be untagged cards and labels to choose from.
     */
    public boolean canRun() {
        return untagged > 0 && labels > 0;
    }
}
