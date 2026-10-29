/*
 * SPDX-FileCopyrightText: 2026 splinteredlight
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package it.niedermann.nextcloud.deck.forkai;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.util.Locale;

/**
 * Classifies a failed AI request.
 * <p>
 * Error responses of the server are JSON <code>{status, message}</code>. On the Single-Sign-On path the body does
 * not arrive verbatim: the Files app wraps it into an exception, so the error body looks like
 * <code>java.lang.IllegalStateException: {"status":400,"message":"..."}</code>. Exceptions inside the Files app
 * (e.g. a read timeout) are reported with the pseudo status code {@link #SSO_EXCEPTION_STATUS} and the exception's
 * text as body.
 */
public class ForkAiError {

    /**
     * Status code the SSO library uses when the request failed with an exception instead of an HTTP response.
     */
    public static final int SSO_EXCEPTION_STATUS = 900;

    /**
     * The Files app performs SSO requests with a fixed read timeout of 60 seconds, and reports a timeout with the
     * same generic network exception as a refused connection. A network failure after this long is a timeout.
     */
    static final long LIKELY_TIMEOUT_AFTER_MILLIS = 50_000;

    private static final int MAX_DETAIL_LENGTH = 300;

    public enum Kind {
        /** No connection (or "sync on Wi-Fi only" without Wi-Fi). The request was not sent. */
        OFFLINE,
        /** 400: not configured, invalid message, board without labels, … */
        BAD_REQUEST,
        /** 401 */
        NOT_LOGGED_IN,
        /** 403 */
        FORBIDDEN,
        /** 404: the server does not offer the endpoints (anymore), or the board is gone */
        NOT_FOUND,
        /** 429 */
        RATE_LIMITED,
        /** 503 */
        MAINTENANCE,
        /** 500 and other 5xx: the server reported a failure (e.g. the AI call failed after its retries) */
        SERVER_ERROR,
        /** 502 / 504 from a proxy: the server may still be working on the request */
        GATEWAY_ERROR,
        /** The client stopped waiting; the server may still finish the request */
        TIMEOUT,
        /** The request was sent but no usable response came back; the server may have processed it */
        NO_RESPONSE,
        /** Failure on the client side before the request was sent */
        CLIENT_ERROR,
        /** Another AI run is still in flight; nothing was sent */
        BUSY,
        /** Any other HTTP status */
        UNKNOWN
    }

    @NonNull
    private final Kind kind;
    private final int httpStatus;
    @Nullable
    private final String serverMessage;
    @Nullable
    private final String technicalDetail;

    private ForkAiError(@NonNull Kind kind, int httpStatus, @Nullable String serverMessage, @Nullable String technicalDetail) {
        this.kind = kind;
        this.httpStatus = httpStatus;
        this.serverMessage = serverMessage;
        this.technicalDetail = technicalDetail;
    }

    @NonNull
    public static ForkAiError offline() {
        return new ForkAiError(Kind.OFFLINE, 0, null, null);
    }

    @NonNull
    public static ForkAiError busy() {
        return new ForkAiError(Kind.BUSY, 0, null, null);
    }

    @NonNull
    public static ForkAiError clientError(@Nullable String technicalDetail) {
        return new ForkAiError(Kind.CLIENT_ERROR, 0, null, shorten(technicalDetail));
    }

    @NonNull
    public static ForkAiError fromHttpResponse(int httpStatus, @Nullable String rawErrorBody) {
        return fromHttpResponse(httpStatus, rawErrorBody, 0);
    }

    /**
     * @param elapsedMillis how long the request took until it failed
     */
    @NonNull
    public static ForkAiError fromHttpResponse(int httpStatus, @Nullable String rawErrorBody, long elapsedMillis) {
        final var serverMessage = extractMessage(rawErrorBody);
        final Kind kind;
        String technicalDetail = null;
        switch (httpStatus) {
            case 400 -> kind = Kind.BAD_REQUEST;
            case 401 -> kind = Kind.NOT_LOGGED_IN;
            case 403 -> kind = Kind.FORBIDDEN;
            case 404 -> kind = Kind.NOT_FOUND;
            case 429 -> kind = Kind.RATE_LIMITED;
            case 502, 504 -> kind = Kind.GATEWAY_ERROR;
            case 503 -> kind = Kind.MAINTENANCE;
            case SSO_EXCEPTION_STATUS -> {
                kind = classifyException(rawErrorBody, elapsedMillis);
                technicalDetail = shorten(rawErrorBody);
            }
            default -> {
                kind = httpStatus >= 500 && httpStatus < 600 ? Kind.SERVER_ERROR : Kind.UNKNOWN;
                if (serverMessage == null) {
                    technicalDetail = shorten(rawErrorBody);
                }
            }
        }
        // An exception text is not a message from the Deck server
        return new ForkAiError(kind, httpStatus, httpStatus == SSO_EXCEPTION_STATUS ? null : serverMessage, technicalDetail);
    }

    @NonNull
    private static Kind classifyException(@Nullable String exceptionText, long elapsedMillis) {
        if (exceptionText == null) {
            return elapsedMillis >= LIKELY_TIMEOUT_AFTER_MILLIS ? Kind.TIMEOUT : Kind.NO_RESPONSE;
        }
        final var text = exceptionText.toLowerCase(Locale.ROOT);
        // SSO problems between this app and the Files app: nothing was sent to the server
        if (text.contains("tokenmismatchexception")
                || text.contains("nextcloudapinotrespondingexception")
                || text.contains("nextcloudfilesappaccountnotfoundexception")
                || text.contains("nextcloudfilesappnotinstalledexception")
                || text.contains("nextcloudfilesappnotsupportedexception")
                || text.contains("nextcloudfilesappaccountpermissionnotgrantedexception")
                || text.contains("nextcloudunsupportedmethodexception")
                || text.contains("nextcloudinvalidrequesturlexception")) {
            return Kind.CLIENT_ERROR;
        }
        if (text.contains("sockettimeoutexception") || text.contains("timed out") || text.contains("timeout")) {
            return Kind.TIMEOUT;
        }
        // Failures that happen before anything was sent
        if (text.contains("unknownhostexception")
                || text.contains("unable to resolve host")
                || text.contains("connectexception")
                || text.contains("connection refused")
                || text.contains("econnrefused")
                || text.contains("no address associated with hostname")) {
            return Kind.OFFLINE;
        }
        // The SSO library reports timeouts, resets and refused connections all as the same network exception
        if (elapsedMillis >= LIKELY_TIMEOUT_AFTER_MILLIS) {
            return Kind.TIMEOUT;
        }
        return Kind.NO_RESPONSE;
    }

    /**
     * @return the <code>message</code> of a JSON error object somewhere in the body, or <code>null</code>
     */
    @Nullable
    public static String extractMessage(@Nullable String rawErrorBody) {
        if (rawErrorBody == null) {
            return null;
        }
        final int start = rawErrorBody.indexOf('{');
        if (start < 0) {
            return null;
        }
        final var candidates = new String[]{
                rawErrorBody.substring(start),
                rawErrorBody.substring(start, Math.max(start, rawErrorBody.lastIndexOf('}') + 1))
        };
        for (final var candidate : candidates) {
            try {
                final JsonElement element = JsonParser.parseString(candidate);
                if (element.isJsonObject()) {
                    final var message = element.getAsJsonObject().get("message");
                    if (message != null && message.isJsonPrimitive()) {
                        final var text = message.getAsString().trim();
                        return text.isEmpty() ? null : text;
                    }
                    return null;
                }
            } catch (RuntimeException ignored) {
                // try the next candidate
            }
        }
        return null;
    }

    @Nullable
    private static String shorten(@Nullable String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        final var trimmed = text.trim();
        return trimmed.length() <= MAX_DETAIL_LENGTH ? trimmed : trimmed.substring(0, MAX_DETAIL_LENGTH) + "…";
    }

    @NonNull
    public Kind getKind() {
        return kind;
    }

    /**
     * @return the HTTP status, {@link #SSO_EXCEPTION_STATUS}, or <code>0</code> if no request was made
     */
    public int getHttpStatus() {
        return httpStatus;
    }

    /**
     * @return the server's <code>message</code>, which should be shown to the user as is
     */
    @Nullable
    public String getServerMessage() {
        return serverMessage;
    }

    @Nullable
    public String getTechnicalDetail() {
        return technicalDetail;
    }

    /**
     * @return <code>true</code> if the server may have carried out the request anyway, so the board must be
     * refreshed and the user should check it before sending the same request again
     */
    public boolean isOutcomeUnknown() {
        return kind == Kind.TIMEOUT || kind == Kind.NO_RESPONSE || kind == Kind.GATEWAY_ERROR;
    }

    /**
     * @return <code>true</code> if a request reached the server and a refresh might show changes
     */
    public boolean shouldRefreshBoard() {
        return isOutcomeUnknown() || kind == Kind.SERVER_ERROR;
    }

    @NonNull
    @Override
    public String toString() {
        return "ForkAiError{" + kind + ", status=" + httpStatus + ", message=" + serverMessage + ", detail=" + technicalDetail + '}';
    }
}
