/*
 * SPDX-FileCopyrightText: 2026 splinteredlight
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package it.niedermann.nextcloud.deck.forkai;

import android.app.Application;

import androidx.annotation.AnyThread;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import it.niedermann.nextcloud.deck.forkai.model.AiRunResult;
import it.niedermann.nextcloud.deck.forkai.model.AssistantRequest;
import it.niedermann.nextcloud.deck.forkai.model.AutoTagStatus;
import it.niedermann.nextcloud.deck.model.Account;
import it.niedermann.nextcloud.deck.model.Card;

/**
 * State holder of one AI dialog; survives configuration changes.
 * A run that is in flight when the dialog is closed still completes and still refreshes the board.
 */
public class ForkAiViewModel extends AndroidViewModel {

    private final MutableLiveData<ForkAiState> state$ = new MutableLiveData<>(new ForkAiState());
    private final Object lock = new Object();
    @NonNull
    private ForkAiState state = new ForkAiState();
    @Nullable
    private ForkAiRepository repository;
    private long remoteBoardId;
    private boolean statusRequested = false;
    private boolean clearInputPending = false;

    public ForkAiViewModel(@NonNull Application application) {
        super(application);
    }

    @MainThread
    public void init(@NonNull Account account, long remoteBoardId) {
        if (repository == null) {
            this.repository = new ForkAiRepository(getApplication(), account);
            this.remoteBoardId = remoteBoardId;
        }
    }

    public LiveData<ForkAiState> getState() {
        return state$;
    }

    /**
     * @see ForkAiRepository#isRunning()
     */
    public LiveData<Boolean> isAnyRunInFlight() {
        return ForkAiRepository.isRunning();
    }

    /**
     * Loads the auto-tag status once per dialog.
     */
    @MainThread
    public void loadAutoTagStatusOnce() {
        if (statusRequested) {
            return;
        }
        statusRequested = true;
        reloadAutoTagStatus();
    }

    @MainThread
    public void reloadAutoTagStatus() {
        update(ForkAiState::loadingStatus);
        requireRepository().loadAutoTagStatus(remoteBoardId, new ForkAiRepository.Callback<>() {
            @Override
            public void onSuccess(@NonNull AutoTagStatus result) {
                update(s -> s.withStatus(result));
            }

            @Override
            public void onError(@NonNull ForkAiError error) {
                update(s -> s.statusFailed(error));
            }
        });
    }

    @MainThread
    public void runAssistant(@NonNull String message) {
        if (!AssistantRequest.isValidMessage(message) || !tryStartRun()) {
            return;
        }
        if (!requireRepository().runAssistant(remoteBoardId, message, new RunListener(true))) {
            update(s -> s.idle(ForkAiError.busy()));
        }
    }

    @MainThread
    public void runAutoTag() {
        final var status = getCurrentState().getStatus();
        if (status == null || !status.canRun() || getCurrentState().getResult() != null || !tryStartRun()) {
            return;
        }
        if (!requireRepository().runAutoTag(remoteBoardId, new RunListener(false))) {
            update(s -> s.idle(ForkAiError.busy()));
        }
    }

    /**
     * Looks up the local card for a remote card id. The callback runs on a background thread and receives
     * <code>null</code> if the card has not been synchronized yet.
     */
    @AnyThread
    public void findLocalCard(long remoteCardId, @NonNull Consumer<Card> callback) {
        requireRepository().findLocalCard(remoteCardId, callback);
    }

    /**
     * @return <code>true</code> exactly once after a successful assistant run, so the input can be cleared
     */
    @MainThread
    public boolean consumeClearInput() {
        synchronized (lock) {
            final boolean pending = clearInputPending;
            clearInputPending = false;
            return pending;
        }
    }

    @NonNull
    private ForkAiState getCurrentState() {
        synchronized (lock) {
            return state;
        }
    }

    /**
     * Blocks double submits from this dialog before the repository's process-wide guard is even reached.
     */
    private boolean tryStartRun() {
        synchronized (lock) {
            if (state.isBusy()) {
                return false;
            }
            state = state.running(ForkAiState.Phase.RUNNING);
            state$.postValue(state);
            return true;
        }
    }

    @AnyThread
    private void update(@NonNull UnaryOperator<ForkAiState> mutation) {
        synchronized (lock) {
            state = mutation.apply(state);
            state$.postValue(state);
        }
    }

    @NonNull
    private ForkAiRepository requireRepository() {
        if (repository == null) {
            throw new IllegalStateException("Call init() first");
        }
        return repository;
    }

    private class RunListener implements ForkAiRepository.RunListener {

        private final boolean clearInputOnSuccess;

        private RunListener(boolean clearInputOnSuccess) {
            this.clearInputOnSuccess = clearInputOnSuccess;
        }

        @Override
        public void onSyncingLocalChanges() {
            update(s -> s.running(ForkAiState.Phase.SYNCING_LOCAL_CHANGES));
        }

        @Override
        public void onRunning() {
            update(s -> s.running(ForkAiState.Phase.RUNNING));
        }

        @Override
        public void onSuccess(@NonNull AiRunResult result) {
            synchronized (lock) {
                if (clearInputOnSuccess) {
                    clearInputPending = true;
                }
                state = state.done(result);
                state$.postValue(state);
            }
        }

        @Override
        public void onError(@NonNull ForkAiError error) {
            update(s -> s.failed(error));
        }

        @Override
        public void onRefreshStarted() {
            update(s -> s.withRefresh(ForkAiState.Refresh.REFRESHING));
        }

        @Override
        public void onRefreshFinished(boolean success) {
            update(s -> s.withRefresh(success ? ForkAiState.Refresh.REFRESHED : ForkAiState.Refresh.FAILED));
        }
    }
}
