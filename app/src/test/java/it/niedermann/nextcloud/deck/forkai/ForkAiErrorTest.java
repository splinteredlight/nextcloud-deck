/*
 * SPDX-FileCopyrightText: 2026 splinteredlight
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package it.niedermann.nextcloud.deck.forkai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Error bodies as they arrive through the Single-Sign-On library: the Files app wraps the server's JSON
 * <code>{status, message}</code> into an exception text, and reports its own exceptions as status 900.
 */
public class ForkAiErrorTest {

    private static String ssoWrapped(String body) {
        return "java.lang.IllegalStateException: " + body;
    }

    @Test
    public void badRequest_showsServerMessage() {
        final var error = ForkAiError.fromHttpResponse(400, ssoWrapped("{\"status\":400,\"message\":\"The request text is empty.\"}"));
        assertEquals(ForkAiError.Kind.BAD_REQUEST, error.getKind());
        assertEquals(400, error.getHttpStatus());
        assertEquals("The request text is empty.", error.getServerMessage());
        assertFalse(error.isOutcomeUnknown());
        assertFalse(error.shouldRefreshBoard());
    }

    @Test
    public void notConfigured_and_noLabels_areBadRequests() {
        assertEquals("The AI is not configured on this server.",
                ForkAiError.fromHttpResponse(400, ssoWrapped("{\"status\":400,\"message\":\"The AI is not configured on this server.\"}")).getServerMessage());
        assertEquals(ForkAiError.Kind.BAD_REQUEST,
                ForkAiError.fromHttpResponse(400, ssoWrapped("{\"status\":400,\"message\":\"This board has no labels.\"}")).getKind());
    }

    @Test
    public void forbidden() {
        final var error = ForkAiError.fromHttpResponse(403, ssoWrapped("{\"status\":403,\"message\":\"Permission denied\"}"));
        assertEquals(ForkAiError.Kind.FORBIDDEN, error.getKind());
        assertEquals("Permission denied", error.getServerMessage());
    }

    @Test
    public void rateLimited_withoutJsonMessage() {
        // Nextcloud's rate limiter answers with an empty JSON array, not with {status, message}
        final var error = ForkAiError.fromHttpResponse(429, ssoWrapped("[]"));
        assertEquals(ForkAiError.Kind.RATE_LIMITED, error.getKind());
        assertNull(error.getServerMessage());
        assertFalse(error.isOutcomeUnknown());
    }

    @Test
    public void serverError_refreshesTheBoard() {
        final var error = ForkAiError.fromHttpResponse(500, ssoWrapped("{\"status\":500,\"message\":\"The AI call failed.\"}"));
        assertEquals(ForkAiError.Kind.SERVER_ERROR, error.getKind());
        assertEquals("The AI call failed.", error.getServerMessage());
        assertFalse(error.isOutcomeUnknown());
        assertTrue(error.shouldRefreshBoard());
    }

    @Test
    public void gatewayErrors_haveAnUnknownOutcome() {
        for (final int status : new int[]{502, 504}) {
            final var error = ForkAiError.fromHttpResponse(status, ssoWrapped("<html>Gateway Timeout</html>"));
            assertEquals(ForkAiError.Kind.GATEWAY_ERROR, error.getKind());
            assertNull(error.getServerMessage());
            assertTrue(error.isOutcomeUnknown());
            assertTrue(error.shouldRefreshBoard());
        }
    }

    @Test
    public void maintenance_notFound_notLoggedIn() {
        assertEquals(ForkAiError.Kind.MAINTENANCE, ForkAiError.fromHttpResponse(503, null).getKind());
        assertEquals(ForkAiError.Kind.NOT_FOUND, ForkAiError.fromHttpResponse(404, ssoWrapped("No response body")).getKind());
        final var notLoggedIn = ForkAiError.fromHttpResponse(401, ssoWrapped("{\"message\":\"Current user is not logged in\"}"));
        assertEquals(ForkAiError.Kind.NOT_LOGGED_IN, notLoggedIn.getKind());
        assertEquals("Current user is not logged in", notLoggedIn.getServerMessage());
    }

    @Test
    public void unknownStatus_keepsTheBodyAsDetail() {
        final var error = ForkAiError.fromHttpResponse(418, ssoWrapped("I'm a teapot"));
        assertEquals(ForkAiError.Kind.UNKNOWN, error.getKind());
        assertNull(error.getServerMessage());
        assertNotNull(error.getTechnicalDetail());
    }

    @Test
    public void plainJsonBody_isUnderstoodToo() {
        assertEquals("Permission denied", ForkAiError.fromHttpResponse(403, "{\"status\":403,\"message\":\"Permission denied\"}").getServerMessage());
    }

    @Test
    public void ssoNetworkException_quick_isNoResponse() {
        final var error = ForkAiError.fromHttpResponse(ForkAiError.SSO_EXCEPTION_STATUS,
                "com.nextcloud.android.sso.exceptions.NextcloudNetworkException: Network connection failed.", 3_000);
        assertEquals(ForkAiError.Kind.NO_RESPONSE, error.getKind());
        assertNull(error.getServerMessage());
        assertTrue(error.isOutcomeUnknown());
        assertTrue(error.shouldRefreshBoard());
    }

    @Test
    public void ssoNetworkException_afterTheReadTimeout_isTimeout() {
        final var error = ForkAiError.fromHttpResponse(ForkAiError.SSO_EXCEPTION_STATUS,
                "com.nextcloud.android.sso.exceptions.NextcloudNetworkException: Network connection failed.", 60_400);
        assertEquals(ForkAiError.Kind.TIMEOUT, error.getKind());
        assertTrue(error.isOutcomeUnknown());
    }

    @Test
    public void explicitTimeoutText_isTimeout() {
        assertEquals(ForkAiError.Kind.TIMEOUT,
                ForkAiError.fromHttpResponse(ForkAiError.SSO_EXCEPTION_STATUS, "java.net.SocketTimeoutException: Read timed out", 1_000).getKind());
    }

    @Test
    public void ssoSetupProblems_wereNeverSent() {
        final var error = ForkAiError.fromHttpResponse(ForkAiError.SSO_EXCEPTION_STATUS,
                "com.nextcloud.android.sso.exceptions.TokenMismatchException: Token mismatch", 200);
        assertEquals(ForkAiError.Kind.CLIENT_ERROR, error.getKind());
        assertFalse(error.isOutcomeUnknown());
        assertFalse(error.shouldRefreshBoard());
    }

    @Test
    public void connectFailures_areOffline() {
        final var error = ForkAiError.fromHttpResponse(ForkAiError.SSO_EXCEPTION_STATUS,
                "java.net.UnknownHostException: Unable to resolve host \"cloud.example.com\"", 100);
        assertEquals(ForkAiError.Kind.OFFLINE, error.getKind());
        assertFalse(error.isOutcomeUnknown());
    }

    @Test
    public void exceptionText_isNeverShownAsServerMessage() {
        final var error = ForkAiError.fromHttpResponse(ForkAiError.SSO_EXCEPTION_STATUS,
                "com.google.gson.JsonSyntaxException: {\"message\":\"looks like JSON\"}", 1_000);
        assertNull(error.getServerMessage());
        assertEquals(ForkAiError.Kind.NO_RESPONSE, error.getKind());
    }

    @Test
    public void extractMessage_edgeCases() {
        assertNull(ForkAiError.extractMessage(null));
        assertNull(ForkAiError.extractMessage(""));
        assertNull(ForkAiError.extractMessage("no json here"));
        assertNull(ForkAiError.extractMessage("{\"status\":500}"));
        assertNull(ForkAiError.extractMessage("{\"message\":\"   \"}"));
        assertNull(ForkAiError.extractMessage("{broken"));
        assertEquals("trailing text", ForkAiError.extractMessage("prefix {\"message\":\"trailing text\"} suffix"));
    }

    @Test
    public void localErrors() {
        assertEquals(ForkAiError.Kind.OFFLINE, ForkAiError.offline().getKind());
        assertEquals(ForkAiError.Kind.BUSY, ForkAiError.busy().getKind());
        assertFalse(ForkAiError.busy().shouldRefreshBoard());
        assertEquals(ForkAiError.Kind.CLIENT_ERROR, ForkAiError.clientError("x").getKind());
    }
}
