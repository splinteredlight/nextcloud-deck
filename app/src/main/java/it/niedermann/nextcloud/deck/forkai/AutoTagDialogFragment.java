/*
 * SPDX-FileCopyrightText: 2026 splinteredlight
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package it.niedermann.nextcloud.deck.forkai;

import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.nextcloud.android.common.ui.theme.utils.ColorRole;

import java.util.Objects;

import it.niedermann.nextcloud.deck.R;
import it.niedermann.nextcloud.deck.databinding.DialogForkAiAutotagBinding;
import it.niedermann.nextcloud.deck.model.Account;
import it.niedermann.nextcloud.deck.ui.theme.ThemeUtils;
import it.niedermann.nextcloud.deck.ui.theme.ThemedDialogFragment;

/**
 * Shows how many current cards have no label and lets the server's auto-tag label them.
 */
public class AutoTagDialogFragment extends ThemedDialogFragment {

    private static final String KEY_ACCOUNT = "account";
    private static final String KEY_BOARD_LOCAL_ID = "board_local_id";
    private static final String KEY_BOARD_REMOTE_ID = "board_remote_id";

    private DialogForkAiAutotagBinding binding;
    private ForkAiViewModel viewModel;
    private Account account;
    private long boardLocalId;
    @NonNull
    private ForkAiState state = new ForkAiState();
    private boolean anyRunInFlight = false;

    public static DialogFragment newInstance(@NonNull Account account, long boardLocalId, long boardRemoteId) {
        final var fragment = new AutoTagDialogFragment();
        final var args = new Bundle();
        args.putSerializable(KEY_ACCOUNT, account);
        args.putLong(KEY_BOARD_LOCAL_ID, boardLocalId);
        args.putLong(KEY_BOARD_REMOTE_ID, boardRemoteId);
        fragment.setArguments(args);
        return fragment;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        final var args = requireArguments();
        account = Objects.requireNonNull((Account) args.getSerializable(KEY_ACCOUNT));
        boardLocalId = args.getLong(KEY_BOARD_LOCAL_ID);

        viewModel = new ViewModelProvider(this).get(ForkAiViewModel.class);
        viewModel.init(account, args.getLong(KEY_BOARD_REMOTE_ID));

        binding = DialogForkAiAutotagBinding.inflate(requireActivity().getLayoutInflater());

        final var dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.fork_ai_autotag_title)
                .setView(binding.getRoot())
                .setNegativeButton(R.string.simple_close, null)
                .setPositiveButton(R.string.fork_ai_autotag_run, null)
                .create();

        dialog.setOnShowListener(d -> {
            // Set here instead of in the builder, so a click does not dismiss the dialog
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (canRun()) {
                    viewModel.runAutoTag();
                }
            });
            render();
        });

        viewModel.getState().observe(this, newState -> {
            state = newState;
            render();
        });
        viewModel.isAnyRunInFlight().observe(this, inFlight -> {
            anyRunInFlight = Boolean.TRUE.equals(inFlight);
            render();
        });
        viewModel.loadAutoTagStatusOnce();

        return dialog;
    }

    /**
     * Same rule as the web dialog, plus: no other AI run in flight.
     */
    private boolean canRun() {
        final var status = state.getStatus();
        return !state.isBusy()
                && !anyRunInFlight
                && state.getResult() == null
                && status != null
                && status.canRun();
    }

    private void render() {
        if (binding == null) {
            return;
        }

        final var status = state.getStatus();
        if (status == null || state.getResult() != null || state.getPhase() == ForkAiState.Phase.LOADING_STATUS) {
            binding.status.setVisibility(View.GONE);
        } else {
            if (status.getUntagged() == 0) {
                binding.status.setText(R.string.fork_ai_autotag_all_tagged);
            } else if (status.getLabels() == 0) {
                binding.status.setText(R.string.fork_ai_autotag_no_labels);
            } else {
                binding.status.setText(getResources().getQuantityString(R.plurals.fork_ai_autotag_untagged, status.getUntagged(), status.getUntagged()));
            }
            binding.status.setVisibility(View.VISIBLE);
        }

        final @StringRes int progress;
        switch (state.getPhase()) {
            case LOADING_STATUS -> progress = R.string.fork_ai_autotag_counting;
            case SYNCING_LOCAL_CHANGES -> progress = R.string.fork_ai_syncing_local_changes;
            case RUNNING -> progress = R.string.fork_ai_autotag_tagging;
            default -> progress = anyRunInFlight ? R.string.fork_ai_busy : 0;
        }
        ForkAiResultsRenderer.renderProgress(binding.progress, progress);
        ForkAiResultsRenderer.renderError(binding.error, state.getError());
        ForkAiResultsRenderer.renderResults(binding.results, state, true, this::openCard);

        if (getDialog() instanceof AlertDialog alertDialog) {
            final Button run = alertDialog.getButton(DialogInterface.BUTTON_POSITIVE);
            if (run != null) {
                run.setEnabled(canRun());
            }
        }
    }

    private void openCard(long remoteCardId) {
        ForkAiCardOpener.open(this, viewModel, account, boardLocalId, remoteCardId);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        this.binding = null;
    }

    @Override
    public void applyTheme(int color) {
        if (binding == null) {
            return;
        }
        final var utils = ThemeUtils.of(color, requireContext());
        utils.platform.colorCircularProgressBar(binding.progress.progressBar, ColorRole.PRIMARY);
    }
}
