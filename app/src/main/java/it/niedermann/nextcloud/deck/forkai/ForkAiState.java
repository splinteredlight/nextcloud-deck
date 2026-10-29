/*
 * SPDX-FileCopyrightText: 2026 splinteredlight
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package it.niedermann.nextcloud.deck.forkai;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import it.niedermann.nextcloud.deck.forkai.model.AiRunResult;
import it.niedermann.nextcloud.deck.forkai.model.AutoTagStatus;

/**
 * Immutable UI state of an AI dialog.
 */
public class ForkAiState {

    public enum Phase {
        IDLE,
        /** Auto-tag only: loading the untagged count */
        LOADING_STATUS,
        SYNCING_LOCAL_CHANGES,
        RUNNING,
        /** The run finished, see {@link #getResult()} or {@link #getError()} */
        DONE
    }

    public enum Refresh {
        NONE,
        REFRESHING,
        REFRESHED,
        FAILED
    }

    @NonNull
    private final Phase phase;
    @Nullable
    private final AutoTagStatus status;
    @Nullable
    private final AiRunResult result;
    @Nullable
    private final ForkAiError error;
    @NonNull
    private final Refresh refresh;

    public ForkAiState() {
        this(Phase.IDLE, null, null, null, Refresh.NONE);
    }

    private ForkAiState(@NonNull Phase phase, @Nullable AutoTagStatus status, @Nullable AiRunResult result, @Nullable ForkAiError error, @NonNull Refresh refresh) {
        this.phase = phase;
        this.status = status;
        this.result = result;
        this.error = error;
        this.refresh = refresh;
    }

    @NonNull
    public Phase getPhase() {
        return phase;
    }

    @Nullable
    public AutoTagStatus getStatus() {
        return status;
    }

    @Nullable
    public AiRunResult getResult() {
        return result;
    }

    @Nullable
    public ForkAiError getError() {
        return error;
    }

    @NonNull
    public Refresh getRefresh() {
        return refresh;
    }

    /**
     * @return <code>true</code> while a request of this dialog is in flight
     */
    public boolean isBusy() {
        return phase == Phase.LOADING_STATUS || phase == Phase.SYNCING_LOCAL_CHANGES || phase == Phase.RUNNING;
    }

    @NonNull
    public ForkAiState loadingStatus() {
        return new ForkAiState(Phase.LOADING_STATUS, null, result, null, refresh);
    }

    @NonNull
    public ForkAiState withStatus(@NonNull AutoTagStatus status) {
        return new ForkAiState(Phase.IDLE, status, result, null, refresh);
    }

    @NonNull
    public ForkAiState statusFailed(@NonNull ForkAiError error) {
        return new ForkAiState(Phase.IDLE, null, result, error, refresh);
    }

    /**
     * A new run starts: previous result and error are cleared.
     */
    @NonNull
    public ForkAiState running(@NonNull Phase phase) {
        return new ForkAiState(phase, status, null, null, Refresh.NONE);
    }

    @NonNull
    public ForkAiState done(@NonNull AiRunResult result) {
        return new ForkAiState(Phase.DONE, status, result, null, refresh);
    }

    @NonNull
    public ForkAiState failed(@NonNull ForkAiError error) {
        return new ForkAiState(Phase.DONE, status, null, error, refresh);
    }

    @NonNull
    public ForkAiState withRefresh(@NonNull Refresh refresh) {
        return new ForkAiState(phase, status, result, error, refresh);
    }

    /**
     * Back to idle without a result, e.g. when the run could not start.
     */
    @NonNull
    public ForkAiState idle(@Nullable ForkAiError error) {
        return new ForkAiState(Phase.IDLE, status, null, error, Refresh.NONE);
    }
}
