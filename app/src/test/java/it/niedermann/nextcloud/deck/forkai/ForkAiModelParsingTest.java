/*
 * SPDX-FileCopyrightText: 2026 splinteredlight
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package it.niedermann.nextcloud.deck.forkai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import it.niedermann.nextcloud.deck.forkai.model.AiRunResult;
import it.niedermann.nextcloud.deck.forkai.model.AssistantRequest;
import it.niedermann.nextcloud.deck.forkai.model.AutoTagStatus;
import it.niedermann.nextcloud.deck.remote.api.GsonConfig;

/**
 * Parses responses with the same {@link com.google.gson.Gson} instance the Single-Sign-On API uses.
 */
@RunWith(RobolectricTestRunner.class)
public class ForkAiModelParsingTest {

    private static AiRunResult parseRunResult(String json) {
        return GsonConfig.getGson().fromJson(json, AiRunResult.class);
    }

    @Test
    public void assistantResult() {
        final var result = parseRunResult(ForkAiFixtures.read("assistant_result.json"));

        assertEquals("Created a card for the offsite and added a step to the venue card.", result.getSummary());
        assertEquals(4, result.getResults().size());

        final var created = result.getResults().get(0);
        assertEquals("create_card", created.getType());
        assertTrue(created.isOk());
        assertEquals("Created card \"Plan team offsite\" (2 checklist steps) [Planning]", created.getDetail());
        assertEquals(Long.valueOf(412), created.getCardId());

        final var appended = result.getResults().get(1);
        assertEquals("append_checklist", appended.getType());
        assertEquals(Long.valueOf(377), appended.getCardId());

        // Failed actions come without a cardId
        final var failed = result.getResults().get(2);
        assertEquals("move_card", failed.getType());
        assertFalse(failed.isOk());
        assertNull(failed.getCardId());

        // A successful action may still have a null cardId
        final var edited = result.getResults().get(3);
        assertTrue(edited.isOk());
        assertNull(edited.getCardId());

        assertEquals(1, result.getNotes().size());
        assertEquals("The venue card already covers the booking itself.", result.getNotes().get(0));
    }

    @Test
    public void assistantNothingDone() {
        final var result = parseRunResult(ForkAiFixtures.read("assistant_nothing_done.json"));

        assertEquals("Nothing to do: the board already tracks this.", result.getSummary());
        assertTrue(result.getResults().isEmpty());
        assertEquals(1, result.getNotes().size());
    }

    @Test
    public void autoTagStatus() {
        final var status = GsonConfig.getGson().fromJson(ForkAiFixtures.read("autotag_status.json"), AutoTagStatus.class);

        assertEquals(7, status.getUntagged());
        assertEquals(4, status.getLabels());
        assertTrue(status.canRun());
    }

    @Test
    public void autoTagStatus_canRun() {
        assertFalse(new AutoTagStatus(0, 4).canRun());
        assertFalse(new AutoTagStatus(3, 0).canRun());
        assertTrue(new AutoTagStatus(1, 1).canRun());
    }

    @Test
    public void autoTagResult() {
        final var result = parseRunResult(ForkAiFixtures.read("autotag_result.json"));

        assertEquals("Tagged 2 of 4 untagged cards.", result.getSummary());
        assertEquals(3, result.getResults().size());

        final var tagged = result.getResults().get(1);
        assertNull(tagged.getType());
        assertTrue(tagged.isOk());
        assertEquals(Long.valueOf(502), tagged.getCardId());
        assertEquals("Tagged \"Fix login bug\" with Bug, Urgent", tagged.getDetail());

        // "Left untagged" is a normal outcome, and still links to its card
        final var untagged = result.getResults().get(2);
        assertFalse(untagged.isOk());
        assertEquals(Long.valueOf(503), untagged.getCardId());

        assertEquals(1, result.getNotes().size());
    }

    @Test
    public void autoTagNothingToDo() {
        final var result = parseRunResult(ForkAiFixtures.read("autotag_nothing_to_do.json"));

        assertEquals("All current cards already have labels.", result.getSummary());
        assertTrue(result.getResults().isEmpty());
        assertTrue(result.getNotes().isEmpty());
    }

    @Test
    public void missingOrNullFields_areSafe() {
        final var empty = parseRunResult("{}");
        assertEquals("", empty.getSummary());
        assertTrue(empty.getResults().isEmpty());
        assertTrue(empty.getNotes().isEmpty());

        final var nulls = parseRunResult("{\"summary\": null, \"results\": [null, {\"ok\": true}], \"notes\": [null, \"  \", \"kept\"]}");
        assertEquals("", nulls.getSummary());
        assertEquals(1, nulls.getResults().size());
        assertEquals("", nulls.getResults().get(0).getDetail());
        assertNull(nulls.getResults().get(0).getCardId());
        assertEquals(1, nulls.getNotes().size());
        assertEquals("kept", nulls.getNotes().get(0));
    }

    @Test
    public void withNote_appendsWithoutTouchingTheRest() {
        final var result = parseRunResult(ForkAiFixtures.read("autotag_result.json")).withNote("local note");

        assertEquals("Tagged 2 of 4 untagged cards.", result.getSummary());
        assertEquals(3, result.getResults().size());
        assertEquals(2, result.getNotes().size());
        assertEquals("local note", result.getNotes().get(1));
    }

    @Test
    public void assistantRequest_serializesMessage() {
        assertEquals("{\"message\":\"Book a venue\"}", GsonConfig.getGson().toJson(new AssistantRequest("Book a venue")));
    }

    @Test
    public void assistantRequest_validation() {
        assertFalse(AssistantRequest.isValidMessage(""));
        assertFalse(AssistantRequest.isValidMessage(" \n\t "));
        assertTrue(AssistantRequest.isValidMessage("x"));
        assertTrue(AssistantRequest.isValidMessage("  " + "a".repeat(AssistantRequest.MAX_MESSAGE_LENGTH) + "\n"));
        assertFalse(AssistantRequest.isValidMessage("a".repeat(AssistantRequest.MAX_MESSAGE_LENGTH + 1)));
        // The server counts characters, not UTF-16 units: an emoji is one character
        assertTrue(AssistantRequest.isValidMessage("😀".repeat(AssistantRequest.MAX_MESSAGE_LENGTH)));
        assertFalse(AssistantRequest.isTooLong("😀".repeat(AssistantRequest.MAX_MESSAGE_LENGTH)));
        assertTrue(AssistantRequest.isTooLong("😀".repeat(AssistantRequest.MAX_MESSAGE_LENGTH + 1)));
        assertEquals(2, AssistantRequest.countCharacters("  😀x \n"));
        assertEquals(0, AssistantRequest.countCharacters(null));
    }
}
