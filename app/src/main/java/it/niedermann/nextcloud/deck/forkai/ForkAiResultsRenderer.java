/*
 * SPDX-FileCopyrightText: 2026 splinteredlight
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package it.niedermann.nextcloud.deck.forkai;

import android.content.Context;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

import it.niedermann.nextcloud.deck.R;
import it.niedermann.nextcloud.deck.databinding.IncludeForkAiProgressBinding;
import it.niedermann.nextcloud.deck.databinding.IncludeForkAiResultsBinding;
import it.niedermann.nextcloud.deck.databinding.ItemForkAiResultBinding;
import it.niedermann.nextcloud.deck.forkai.model.AiActionResult;

/**
 * Renders progress, errors and results the same way in both AI dialogs.
 */
final class ForkAiResultsRenderer {

    interface OnCardClickListener {
        void onCardClicked(long remoteCardId);
    }

    private ForkAiResultsRenderer() {
        // Util class
    }

    static void renderProgress(@NonNull IncludeForkAiProgressBinding binding, @StringRes int text) {
        if (text == 0) {
            binding.getRoot().setVisibility(View.GONE);
        } else {
            binding.progressText.setText(text);
            binding.getRoot().setVisibility(View.VISIBLE);
        }
    }

    static void renderError(@NonNull TextView view, @Nullable ForkAiError error) {
        if (error == null) {
            view.setVisibility(View.GONE);
            view.setText(null);
        } else {
            view.setText(getMessage(view.getContext(), error));
            view.setVisibility(View.VISIBLE);
        }
    }

    /**
     * @param autoTag <code>true</code> renders <code>ok == false</code> as a neutral "left untagged" entry instead of a failure
     */
    static void renderResults(@NonNull IncludeForkAiResultsBinding binding,
                              @NonNull ForkAiState state,
                              boolean autoTag,
                              @NonNull OnCardClickListener listener) {
        final var context = binding.getRoot().getContext();
        final var result = state.getResult();
        binding.entries.removeAllViews();

        if (result != null) {
            binding.summary.setText(result.getSummary());
            binding.summary.setVisibility(result.getSummary().isEmpty() ? View.GONE : View.VISIBLE);

            final var inflater = LayoutInflater.from(context);
            for (final var entry : result.getResults()) {
                addEntry(binding, inflater, entry, autoTag, listener);
            }
            for (final var note : result.getNotes()) {
                final var row = ItemForkAiResultBinding.inflate(inflater, binding.entries, false);
                row.marker.setText(R.string.fork_ai_marker_note);
                row.detail.setText(note);
                setTextColor(row.marker, R.color.fork_ai_text_muted);
                setTextColor(row.detail, R.color.fork_ai_text_muted);
                row.getRoot().setContentDescription(context.getString(R.string.fork_ai_result_note) + ": " + note);
                binding.entries.addView(row.getRoot());
            }
        }

        switch (state.getRefresh()) {
            case REFRESHING -> showRefreshState(binding, R.string.fork_ai_refreshing);
            case REFRESHED -> showRefreshState(binding, result != null && hasCardLinks(result.getResults()) ? R.string.fork_ai_refreshed : 0);
            case FAILED -> showRefreshState(binding, R.string.fork_ai_refresh_failed);
            default -> showRefreshState(binding, 0);
        }

        final boolean anythingToShow = result != null || binding.refreshState.getVisibility() == View.VISIBLE;
        binding.getRoot().setVisibility(anythingToShow ? View.VISIBLE : View.GONE);
    }

    private static void addEntry(@NonNull IncludeForkAiResultsBinding binding,
                                 @NonNull LayoutInflater inflater,
                                 @NonNull AiActionResult entry,
                                 boolean autoTag,
                                 @NonNull OnCardClickListener listener) {
        final var context = binding.getRoot().getContext();
        final var row = ItemForkAiResultBinding.inflate(inflater, binding.entries, false);
        final @StringRes int marker;
        final @StringRes int status;
        if (entry.isOk()) {
            marker = R.string.fork_ai_marker_ok;
            status = R.string.fork_ai_result_ok;
            setTextColor(row.marker, R.color.fork_ai_text_ok);
        } else if (autoTag) {
            marker = R.string.fork_ai_marker_skipped;
            status = R.string.fork_ai_result_skipped;
            setTextColor(row.marker, R.color.fork_ai_text_muted);
            setTextColor(row.detail, R.color.fork_ai_text_muted);
        } else {
            marker = R.string.fork_ai_marker_failed;
            status = R.string.fork_ai_result_failed;
            setTextColor(row.marker, R.color.fork_ai_text_error);
            setTextColor(row.detail, R.color.fork_ai_text_error);
        }
        row.marker.setText(marker);
        row.detail.setText(entry.getDetail());
        row.getRoot().setContentDescription(context.getString(status) + ": " + entry.getDetail());

        final var cardId = entry.getCardId();
        if (cardId != null) {
            final var outValue = new TypedValue();
            context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
            row.getRoot().setBackgroundResource(outValue.resourceId);
            row.getRoot().setOnClickListener(v -> listener.onCardClicked(cardId));
            ViewCompat.replaceAccessibilityAction(row.getRoot(),
                    AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
                    context.getString(R.string.fork_ai_result_open_card),
                    null);
        }
        binding.entries.addView(row.getRoot());
    }

    private static boolean hasCardLinks(@NonNull Iterable<AiActionResult> results) {
        for (final var result : results) {
            if (result.getCardId() != null) {
                return true;
            }
        }
        return false;
    }

    private static void showRefreshState(@NonNull IncludeForkAiResultsBinding binding, @StringRes int text) {
        if (text == 0) {
            binding.refreshState.setVisibility(View.GONE);
        } else {
            binding.refreshState.setText(text);
            binding.refreshState.setVisibility(View.VISIBLE);
        }
    }

    private static void setTextColor(@NonNull TextView view, @ColorRes int color) {
        final var colorStateList = AppCompatResources.getColorStateList(view.getContext(), color);
        if (colorStateList != null) {
            view.setTextColor(colorStateList);
        }
    }

    @NonNull
    static String getMessage(@NonNull Context context, @NonNull ForkAiError error) {
        final var message = new StringBuilder();
        final var serverMessage = error.getServerMessage();
        if (serverMessage != null) {
            message.append(serverMessage);
        } else {
            message.append(switch (error.getKind()) {
                case OFFLINE -> context.getString(R.string.fork_ai_error_offline);
                case BAD_REQUEST -> context.getString(R.string.fork_ai_error_bad_request);
                case NOT_LOGGED_IN -> context.getString(R.string.fork_ai_error_not_logged_in);
                case FORBIDDEN -> context.getString(R.string.fork_ai_error_forbidden);
                case NOT_FOUND -> context.getString(R.string.fork_ai_error_not_found);
                case RATE_LIMITED -> context.getString(R.string.fork_ai_error_rate_limited);
                case MAINTENANCE -> context.getString(R.string.fork_ai_error_maintenance);
                case SERVER_ERROR -> context.getString(R.string.fork_ai_error_server);
                case GATEWAY_ERROR -> context.getString(R.string.fork_ai_error_gateway);
                case TIMEOUT -> context.getString(R.string.fork_ai_error_timeout);
                case NO_RESPONSE -> context.getString(R.string.fork_ai_error_no_response);
                case CLIENT_ERROR -> context.getString(R.string.fork_ai_error_client);
                case BUSY -> context.getString(R.string.fork_ai_busy);
                case UNKNOWN -> context.getString(R.string.fork_ai_error_unknown, error.getHttpStatus());
            });
        }
        if (error.isOutcomeUnknown()) {
            message.append("\n\n").append(context.getString(R.string.fork_ai_error_outcome_unknown));
        }
        final var detail = error.getTechnicalDetail();
        if (detail != null && error.getKind() != ForkAiError.Kind.TIMEOUT) {
            message.append("\n\n").append(context.getString(R.string.fork_ai_error_details, detail));
        }
        return message.toString();
    }
}
