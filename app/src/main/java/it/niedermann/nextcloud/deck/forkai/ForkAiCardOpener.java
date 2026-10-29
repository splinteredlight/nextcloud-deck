/*
 * SPDX-FileCopyrightText: 2026 splinteredlight
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package it.niedermann.nextcloud.deck.forkai;

import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import it.niedermann.nextcloud.deck.R;
import it.niedermann.nextcloud.deck.model.Account;
import it.niedermann.nextcloud.deck.ui.card.EditActivity;

/**
 * Opens the card of a result entry, if it has already been synchronized to this device.
 */
final class ForkAiCardOpener {

    private ForkAiCardOpener() {
        // Util class
    }

    static void open(@NonNull Fragment fragment,
                     @NonNull ForkAiViewModel viewModel,
                     @NonNull Account account,
                     long boardLocalId,
                     long remoteCardId) {
        final var appContext = fragment.requireContext().getApplicationContext();
        viewModel.findLocalCard(remoteCardId, card -> {
            final var activity = fragment.getActivity();
            if (activity == null) {
                return;
            }
            activity.runOnUiThread(() -> {
                if (!fragment.isAdded()) {
                    return;
                }
                if (card == null || card.getLocalId() == null) {
                    Toast.makeText(appContext, R.string.fork_ai_card_not_local, Toast.LENGTH_LONG).show();
                } else {
                    fragment.startActivity(EditActivity.createEditCardIntent(activity, account, boardLocalId, card.getLocalId()));
                }
            });
        });
    }
}
