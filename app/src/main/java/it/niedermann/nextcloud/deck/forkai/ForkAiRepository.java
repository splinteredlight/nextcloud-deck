/*
 * SPDX-FileCopyrightText: 2026 splinteredlight
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package it.niedermann.nextcloud.deck.forkai;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.SystemClock;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.nextcloud.android.sso.AccountImporter;
import com.nextcloud.android.sso.api.NextcloudAPI;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

import it.niedermann.nextcloud.deck.DeckLog;
import it.niedermann.nextcloud.deck.R;
import it.niedermann.nextcloud.deck.database.DeckDatabase;
import it.niedermann.nextcloud.deck.forkai.model.AiRunResult;
import it.niedermann.nextcloud.deck.forkai.model.AssistantRequest;
import it.niedermann.nextcloud.deck.forkai.model.AutoTagStatus;
import it.niedermann.nextcloud.deck.model.Account;
import it.niedermann.nextcloud.deck.model.Card;
import it.niedermann.nextcloud.deck.remote.api.GsonConfig;
import it.niedermann.nextcloud.deck.remote.api.ResponseCallback;
import it.niedermann.nextcloud.deck.remote.helpers.util.ConnectivityUtil;
import it.niedermann.nextcloud.deck.repository.SyncRepository;
import okhttp3.Headers;
import retrofit2.Call;
import retrofit2.NextcloudRetrofitApiBuilder;
import retrofit2.Response;

/**
 * Talks to the fork's AI endpoints.
 * <ul>
 *     <li>Requests run on a dedicated single thread, not on the app's shared executor (which re-submits failed
 *     tasks) and not through {@link it.niedermann.nextcloud.deck.remote.api.RequestHelper}. Each POST is executed
 *     exactly once; failures are reported, never retried.</li>
 *     <li>Only one AI run at a time, process-wide (see {@link #isRunning()}), so a reopened dialog can't fire a
 *     second run while the first one is still waiting for the server.</li>
 *     <li>Before a run, pending local changes are pushed with the regular synchronization so the server's board
 *     snapshot is current; after a run (or when the outcome is unknown) the regular synchronization pulls the
 *     changes.</li>
 * </ul>
 * The HTTP read timeout on the Single-Sign-On path is owned by the Nextcloud Files app (see FORK-NOTES.md).
 */
public class ForkAiRepository {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        final var thread = new Thread(runnable, "fork-ai");
        thread.setDaemon(true);
        return thread;
    });
    private static final ExecutorService DB_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final MutableLiveData<Boolean> running$ = new MutableLiveData<>(false);
    private static final long PRE_SYNC_TIMEOUT_SECONDS = 90;

    @NonNull
    private final Context context;
    @NonNull
    private final Account account;
    @NonNull
    private final ConnectivityUtil connectivityUtil;
    @Nullable
    private SyncRepository syncRepository;

    public interface Callback<T> {
        @WorkerThread
        void onSuccess(@NonNull T result);

        @WorkerThread
        void onError(@NonNull ForkAiError error);
    }

    public interface RunListener extends Callback<AiRunResult> {
        /**
         * Pending local changes are being pushed before the run starts.
         */
        @WorkerThread
        void onSyncingLocalChanges();

        /**
         * The request was sent to the server.
         */
        @WorkerThread
        void onRunning();

        /**
         * A synchronization was started to pull the server's changes.
         */
        @WorkerThread
        void onRefreshStarted();

        @AnyThread
        void onRefreshFinished(boolean success);
    }

    public ForkAiRepository(@NonNull Context context, @NonNull Account account) {
        this.context = context.getApplicationContext();
        this.account = account;
        this.connectivityUtil = new ConnectivityUtil(this.context);
    }

    /**
     * @return whether an assistant or auto-tag run is in flight anywhere in the app
     */
    public static LiveData<Boolean> isRunning() {
        return running$;
    }

    @AnyThread
    public void loadAutoTagStatus(long remoteBoardId, @NonNull Callback<AutoTagStatus> callback) {
        EXECUTOR.submit(() -> {
            if (!connectivityUtil.hasInternetConnection()) {
                callback.onError(ForkAiError.offline());
                return;
            }
            deliver(execute(api -> api.getAutoTagStatus(remoteBoardId)), callback);
        });
    }

    /**
     * @return <code>false</code> if another run is still in flight; nothing was started then
     */
    @AnyThread
    public boolean runAssistant(long remoteBoardId, @NonNull String message, @NonNull RunListener listener) {
        return startRun(api -> api.runAssistant(remoteBoardId, new AssistantRequest(message.trim())), listener);
    }

    /**
     * @return <code>false</code> if another run is still in flight; nothing was started then
     */
    @AnyThread
    public boolean runAutoTag(long remoteBoardId, @NonNull RunListener listener) {
        return startRun(api -> api.runAutoTag(remoteBoardId), listener);
    }

    /**
     * Resolves a remote card id from a result to the local card, if it has been synchronized already.
     */
    @AnyThread
    public void findLocalCard(long remoteCardId, @NonNull Consumer<Card> callback) {
        DB_EXECUTOR.submit(() -> {
            Card card = null;
            try {
                card = DeckDatabase.getInstance(context).getCardDao().getCardByRemoteIdDirectly(account.getId(), remoteCardId);
            } catch (Exception e) {
                DeckLog.logError(e);
            }
            callback.accept(card);
        });
    }

    private boolean startRun(@NonNull Function<ForkAiApi, Call<AiRunResult>> callFactory, @NonNull RunListener listener) {
        if (!RUNNING.compareAndSet(false, true)) {
            return false;
        }
        running$.postValue(true);
        try {
            EXECUTOR.submit(() -> {
                try {
                    run(callFactory, listener);
                } catch (Throwable t) {
                    DeckLog.logError(t);
                    listener.onError(ForkAiError.clientError(t.toString()));
                } finally {
                    RUNNING.set(false);
                    running$.postValue(false);
                }
            });
        } catch (RuntimeException e) {
            RUNNING.set(false);
            running$.postValue(false);
            throw e;
        }
        return true;
    }

    @WorkerThread
    private void run(@NonNull Function<ForkAiApi, Call<AiRunResult>> callFactory, @NonNull RunListener listener) {
        if (!connectivityUtil.hasInternetConnection()) {
            listener.onError(ForkAiError.offline());
            return;
        }

        final SyncRepository sync;
        try {
            sync = getSyncRepository();
        } catch (Exception e) {
            DeckLog.logError(e);
            listener.onError(ForkAiError.clientError(e.toString()));
            return;
        }

        boolean localChangesPushed = true;
        if (hasPendingLocalChanges()) {
            listener.onSyncingLocalChanges();
            localChangesPushed = synchronizeAndWait(sync);
            if (!localChangesPushed) {
                DeckLog.warn("Fork AI: local changes could not be pushed before the run, sending anyway");
            }
        }

        listener.onRunning();
        final var outcome = execute(callFactory);

        if (outcome.result != null) {
            listener.onSuccess(localChangesPushed
                    ? outcome.result
                    : outcome.result.withNote(context.getString(R.string.fork_ai_note_local_changes_not_pushed)));
            refresh(sync, listener);
        } else {
            final var error = outcome.error == null ? ForkAiError.clientError(null) : outcome.error;
            listener.onError(error);
            if (error.shouldRefreshBoard()) {
                refresh(sync, listener);
            }
        }
    }

    /**
     * One instance per repository: each {@link SyncRepository} holds its own Single-Sign-On connection.
     */
    @WorkerThread
    @NonNull
    private synchronized SyncRepository getSyncRepository() throws Exception {
        if (syncRepository == null) {
            syncRepository = new SyncRepository(context, account);
        }
        return syncRepository;
    }

    /**
     * Cheap check whether the up-sync has anything to push for this account.
     */
    @WorkerThread
    private boolean hasPendingLocalChanges() {
        try {
            final var db = DeckDatabase.getInstance(context);
            final long accountId = account.getId();
            return !db.getBoardDao().getLocallyChangedBoardsDirectly(accountId).isEmpty()
                    || !db.getStackDao().getLocallyChangedStacksDirectly(accountId).isEmpty()
                    || !db.getCardDao().getLocallyChangedCardsDirectly(accountId).isEmpty()
                    || !db.getLabelDao().getLocallyChangedLabelsDirectly(accountId).isEmpty()
                    || !db.getJoinCardWithLabelDao().getAllChangedJoins().isEmpty();
        } catch (Exception e) {
            DeckLog.logError(e);
            return false;
        }
    }

    /**
     * Runs the regular full synchronization (which pushes local changes) and waits a bounded time for it.
     *
     * @return <code>true</code> if the synchronization finished successfully in time
     */
    @WorkerThread
    private boolean synchronizeAndWait(@NonNull SyncRepository syncRepository) {
        final var latch = new CountDownLatch(1);
        final var success = new AtomicBoolean(false);
        syncRepository.synchronize(new ResponseCallback<>(account) {
            @Override
            public void onResponse(Boolean response, Headers headers) {
                success.set(true);
                latch.countDown();
            }

            @SuppressLint("MissingSuperCall")
            @Override
            public void onError(Throwable throwable) {
                DeckLog.logError(throwable);
                latch.countDown();
            }
        });
        try {
            return latch.await(PRE_SYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS) && success.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @WorkerThread
    private void refresh(@NonNull SyncRepository syncRepository, @NonNull RunListener listener) {
        listener.onRefreshStarted();
        try {
            syncRepository.synchronize(new ResponseCallback<>(account) {
                @Override
                public void onResponse(Boolean response, Headers headers) {
                    listener.onRefreshFinished(true);
                }

                @SuppressLint("MissingSuperCall")
                @Override
                public void onError(Throwable throwable) {
                    DeckLog.logError(throwable);
                    listener.onRefreshFinished(false);
                }
            });
        } catch (Exception e) {
            DeckLog.logError(e);
            listener.onRefreshFinished(false);
        }
    }

    private static <T> void deliver(@NonNull Outcome<T> outcome, @NonNull Callback<T> callback) {
        if (outcome.result != null) {
            callback.onSuccess(outcome.result);
        } else {
            callback.onError(outcome.error == null ? ForkAiError.clientError(null) : outcome.error);
        }
    }

    /**
     * Executes the call exactly once on the current thread through a fresh Single-Sign-On connection.
     */
    @WorkerThread
    @NonNull
    private <T> Outcome<T> execute(@NonNull Function<ForkAiApi, Call<T>> callFactory) {
        final NextcloudAPI nextcloudAPI;
        try {
            final var ssoAccount = AccountImporter.getSingleSignOnAccount(context, account.getName());
            nextcloudAPI = new NextcloudAPI(context, ssoAccount, GsonConfig.getGson(), DeckLog::logError);
        } catch (Exception e) {
            DeckLog.logError(e);
            return Outcome.error(ForkAiError.clientError(e.toString()));
        }

        final long start = SystemClock.elapsedRealtime();
        try {
            final var api = new NextcloudRetrofitApiBuilder(nextcloudAPI, ForkAiApi.ENDPOINT).create(ForkAiApi.class);
            final Response<T> response = callFactory.apply(api).execute();
            if (response.isSuccessful() && response.body() != null) {
                return Outcome.success(response.body());
            }
            final var error = ForkAiError.fromHttpResponse(response.code(), readErrorBody(response), SystemClock.elapsedRealtime() - start);
            DeckLog.warn("Fork AI request failed:", error);
            return Outcome.error(error);
        } catch (Exception e) {
            // The SSO call adapter reports failures as responses; anything thrown here happened before sending.
            DeckLog.logError(e);
            return Outcome.error(ForkAiError.clientError(e.toString()));
        } finally {
            try {
                nextcloudAPI.close();
            } catch (Exception e) {
                DeckLog.logError(e);
            }
        }
    }

    @Nullable
    private static String readErrorBody(@NonNull Response<?> response) {
        try (final var body = response.errorBody()) {
            return body == null ? null : body.string();
        } catch (Exception e) {
            DeckLog.logError(e);
            return null;
        }
    }

    private static class Outcome<T> {
        @Nullable
        final T result;
        @Nullable
        final ForkAiError error;

        private Outcome(@Nullable T result, @Nullable ForkAiError error) {
            this.result = result;
            this.error = error;
        }

        static <T> Outcome<T> success(@NonNull T result) {
            return new Outcome<>(result, null);
        }

        static <T> Outcome<T> error(@NonNull ForkAiError error) {
            return new Outcome<>(null, error);
        }
    }
}
