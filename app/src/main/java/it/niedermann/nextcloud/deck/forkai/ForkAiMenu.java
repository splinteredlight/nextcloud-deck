/*
 * SPDX-FileCopyrightText: 2026 splinteredlight
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package it.niedermann.nextcloud.deck.forkai;

import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.fragment.app.FragmentActivity;

import it.niedermann.nextcloud.deck.R;
import it.niedermann.nextcloud.deck.model.Account;
import it.niedermann.nextcloud.deck.model.full.FullBoard;
import it.niedermann.nextcloud.deck.remote.helpers.util.ConnectivityUtil;

/**
 * Adds "AI assistant" and "Auto-tag" to the board's list menu (⋮ next to the list tabs).
 * <p>
 * Shown like the web UI shows its buttons (edit permission, board not archived; the archived cards view is a
 * separate screen on Android), and additionally only if the server advertises the fork's AI endpoints, the board
 * exists on the server and the device is online. If the server has the endpoints but the AI is not set up, the
 * entries are shown disabled with a hint.
 */
public class ForkAiMenu {

    @NonNull
    private final FragmentActivity activity;
    @NonNull
    private final Menu menu;
    @NonNull
    private final ConnectivityUtil connectivityUtil;
    @Nullable
    private Account account;
    @Nullable
    private FullBoard board;

    public ForkAiMenu(@NonNull FragmentActivity activity, @NonNull Menu menu) {
        this.activity = activity;
        this.menu = menu;
        this.connectivityUtil = new ConnectivityUtil(activity);
        activity.getMenuInflater().inflate(R.menu.fork_ai_menu, menu);
    }

    public void setBoard(@Nullable Account account, @Nullable FullBoard board) {
        this.account = account;
        this.board = board;
        updateVisibility();
    }

    /**
     * Call right before the menu is shown: connectivity and the server's capabilities may have changed.
     */
    public void updateVisibility() {
        final var capability = account == null ? null : ForkAiCapabilityStore.get(activity, account.getId());
        final boolean visible = capability != null && isEligible(board) && connectivityUtil.hasInternetConnection();
        final boolean enabled = visible && capability.isConfigured();
        apply(menu.findItem(R.id.fork_ai_assistant), R.string.fork_ai_menu_assistant, visible, enabled);
        apply(menu.findItem(R.id.fork_ai_autotag), R.string.fork_ai_menu_autotag, visible, enabled);
    }

    /**
     * @return <code>true</code> if the item was one of the AI entries and has been handled
     */
    public boolean onMenuItemSelected(@NonNull MenuItem item) {
        final int itemId = item.getItemId();
        if (itemId != R.id.fork_ai_assistant && itemId != R.id.fork_ai_autotag) {
            return false;
        }
        if (account == null || !isEligible(board)) {
            return true;
        }
        final long localBoardId = board.getLocalId();
        final long remoteBoardId = board.getBoard().getId();
        final var dialog = itemId == R.id.fork_ai_assistant
                ? AssistantDialogFragment.newInstance(account, localBoardId, remoteBoardId)
                : AutoTagDialogFragment.newInstance(account, localBoardId, remoteBoardId);
        dialog.show(activity.getSupportFragmentManager(), dialog.getClass().getSimpleName());
        return true;
    }

    private static boolean isEligible(@Nullable FullBoard board) {
        return board != null
                && board.getLocalId() != null
                && board.getBoard() != null
                && board.getBoard().getId() != null
                && board.getBoard().isPermissionEdit()
                && !board.getBoard().isArchived();
    }

    private void apply(@Nullable MenuItem item, @StringRes int title, boolean visible, boolean enabled) {
        if (item == null) {
            return;
        }
        item.setVisible(visible);
        item.setEnabled(enabled);
        item.setTitle(visible && !enabled
                ? activity.getString(R.string.fork_ai_menu_not_configured, activity.getString(title))
                : activity.getString(title));
    }
}
