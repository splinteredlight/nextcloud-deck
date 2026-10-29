/*
 * SPDX-FileCopyrightText: 2026 splinteredlight
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package it.niedermann.nextcloud.deck.forkai.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.annotations.SerializedName;

/**
 * One entry of {@link AiRunResult#getResults()}.
 * <ul>
 *     <li>Assistant: one executed board action. <code>ok == false</code> means the action failed.</li>
 *     <li>Auto-tag: one untagged card. <code>ok == false</code> means the card was left untagged, which is a normal outcome.</li>
 * </ul>
 */
public class AiActionResult {

    /**
     * Assistant only, e.g. <code>create_card</code>, <code>append_checklist</code>, <code>edit_card</code>,
     * <code>move_card</code>, <code>add_labels</code>, <code>archive_card</code>. Absent for auto-tag.
     */
    @SerializedName("type")
    @Nullable
    private String type;

    @SerializedName("ok")
    private boolean ok;

    @SerializedName("detail")
    @Nullable
    private String detail;

    /**
     * Remote card id. Missing or <code>null</code> for failed assistant actions.
     */
    @SerializedName("cardId")
    @Nullable
    private Long cardId;

    public AiActionResult() {
        // Gson
    }

    public AiActionResult(@Nullable String type, boolean ok, @Nullable String detail, @Nullable Long cardId) {
        this.type = type;
        this.ok = ok;
        this.detail = detail;
        this.cardId = cardId;
    }

    @Nullable
    public String getType() {
        return type;
    }

    public boolean isOk() {
        return ok;
    }

    @NonNull
    public String getDetail() {
        return detail == null ? "" : detail;
    }

    @Nullable
    public Long getCardId() {
        return cardId;
    }
}
