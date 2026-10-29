/*
 * SPDX-FileCopyrightText: 2026 splinteredlight
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package it.niedermann.nextcloud.deck.forkai;

import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
import it.niedermann.nextcloud.deck.databinding.DialogForkAiAssistantBinding;
import it.niedermann.nextcloud.deck.forkai.model.AssistantRequest;
import it.niedermann.nextcloud.deck.model.Account;
import it.niedermann.nextcloud.deck.ui.theme.ThemeUtils;
import it.niedermann.nextcloud.deck.ui.theme.ThemedDialogFragment;
import it.niedermann.nextcloud.deck.util.KeyboardUtils;
import it.niedermann.nextcloud.deck.util.OnTextChangedWatcher;

/**
 * Free-form request → the server's assistant creates or updates cards on the board → result list.
 */
public class AssistantDialogFragment extends ThemedDialogFragment {

    private static final String KEY_ACCOUNT = "account";
    private static final String KEY_BOARD_LOCAL_ID = "board_local_id";
    private static final String KEY_BOARD_REMOTE_ID = "board_remote_id";

    private DialogForkAiAssistantBinding binding;
    private ForkAiViewModel viewModel;
    private Account account;
    private long boardLocalId;
    @NonNull
    private ForkAiState state = new ForkAiState();
    private boolean anyRunInFlight = false;

    public static DialogFragment newInstance(@NonNull Account account, long boardLocalId, long boardRemoteId) {
        final var fragment = new AssistantDialogFragment();
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

        binding = DialogForkAiAssistantBinding.inflate(requireActivity().getLayoutInflater());

        final var dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.fork_ai_assistant_title)
                .setView(binding.getRoot())
                .setNegativeButton(R.string.simple_close, null)
                .setPositiveButton(R.string.fork_ai_assistant_send, null)
                .create();

        dialog.setOnShowListener(d -> {
            // Set here instead of in the builder, so a click does not dismiss the dialog
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> submit());
            render();
        });

        // Count like the server: trimmed, in characters (not UTF-16 units)
        binding.inputWrapper.setLengthCounter(AssistantRequest::countCharacters);
        binding.input.addTextChangedListener(new OnTextChangedWatcher(s -> renderInput()));

        viewModel.getState().observe(this, newState -> {
            state = newState;
            if (newState.getResult() != null && viewModel.consumeClearInput()) {
                binding.input.setText(null);
            }
            render();
        });
        viewModel.isAnyRunInFlight().observe(this, inFlight -> {
            anyRunInFlight = Boolean.TRUE.equals(inFlight);
            render();
        });

        return dialog;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        KeyboardUtils.showKeyboardForEditText(binding.input);
        return super.onCreateView(inflater, container, savedInstanceState);
    }

    private void submit() {
        final var text = binding.input.getText();
        if (text == null || !canSubmit()) {
            return;
        }
        viewModel.runAssistant(text.toString());
    }

    private boolean canSubmit() {
        final var text = binding.input.getText();
        return !state.isBusy()
                && !anyRunInFlight
                && text != null
                && AssistantRequest.isValidMessage(text);
    }

    private void render() {
        if (binding == null) {
            return;
        }
        binding.input.setEnabled(!state.isBusy());

        final @StringRes int progress;
        switch (state.getPhase()) {
            case SYNCING_LOCAL_CHANGES -> progress = R.string.fork_ai_syncing_local_changes;
            case RUNNING -> progress = R.string.fork_ai_working;
            default -> progress = anyRunInFlight ? R.string.fork_ai_busy : 0;
        }
        ForkAiResultsRenderer.renderProgress(binding.progress, progress);
        ForkAiResultsRenderer.renderError(binding.error, state.getError());
        ForkAiResultsRenderer.renderResults(binding.results, state, false, this::openCard);

        renderInput();
    }

    /**
     * Validation and the Send button only; cheap enough for every keystroke.
     */
    private void renderInput() {
        if (binding == null) {
            return;
        }
        final var text = binding.input.getText();
        final boolean tooLong = text != null && AssistantRequest.isTooLong(text);
        binding.inputWrapper.setError(tooLong ? getString(R.string.fork_ai_assistant_too_long, AssistantRequest.MAX_MESSAGE_LENGTH) : null);

        if (getDialog() instanceof AlertDialog alertDialog) {
            final Button send = alertDialog.getButton(DialogInterface.BUTTON_POSITIVE);
            if (send != null) {
                send.setEnabled(canSubmit());
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
        utils.material.colorTextInputLayout(binding.inputWrapper);
        utils.platform.colorCircularProgressBar(binding.progress.progressBar, ColorRole.PRIMARY);
    }
}
