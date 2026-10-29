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

import androidx.test.core.app.ApplicationProvider;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import it.niedermann.nextcloud.deck.model.ocs.Capabilities;
import it.niedermann.nextcloud.deck.remote.api.GsonConfig;

/**
 * Feature detection: the AI entries depend on <code>capabilities.deck.forkAi</code>.
 */
@RunWith(RobolectricTestRunner.class)
public class ForkAiCapabilityTest {

    private static JsonObject deck(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static Capabilities parseCapabilities(String fixture) {
        // Same path as the real capabilities request: GsonConfig → NextcloudDeserializer → JsonToEntityParser
        return GsonConfig.getGson().fromJson(ForkAiFixtures.read(fixture), Capabilities.class);
    }

    @Test
    public void parse_present_configured() {
        final var capability = ForkAiCapability.parse(deck("{\"version\": \"1.18.5\", \"forkAi\": {\"version\": 1, \"configured\": true}}"));
        assertNotNull(capability);
        assertEquals(1, capability.getVersion());
        assertTrue(capability.isConfigured());
    }

    @Test
    public void parse_present_notConfigured() {
        final var capability = ForkAiCapability.parse(deck("{\"version\": \"1.18.5\", \"forkAi\": {\"version\": 1, \"configured\": false}}"));
        assertNotNull(capability);
        assertFalse(capability.isConfigured());
    }

    @Test
    public void parse_absent() {
        assertNull(ForkAiCapability.parse(deck("{\"version\": \"1.18.5\", \"canCreateBoards\": true}")));
        assertNull(ForkAiCapability.parse(null));
    }

    @Test
    public void parse_newerVersion_isAccepted() {
        final var capability = ForkAiCapability.parse(deck("{\"forkAi\": {\"version\": 2, \"configured\": true}}"));
        assertNotNull(capability);
        assertEquals(2, capability.getVersion());
    }

    @Test
    public void parse_malformed() {
        assertNull(ForkAiCapability.parse(deck("{\"forkAi\": true}")));
        assertNull(ForkAiCapability.parse(deck("{\"forkAi\": null}")));
        assertNull(ForkAiCapability.parse(deck("{\"forkAi\": []}")));
        assertNull(ForkAiCapability.parse(deck("{\"forkAi\": {}}")));
        assertNull(ForkAiCapability.parse(deck("{\"forkAi\": {\"version\": 0, \"configured\": true}}")));
        assertNull(ForkAiCapability.parse(deck("{\"forkAi\": {\"version\": \"abc\", \"configured\": true}}")));

        // Only a real JSON true counts as configured
        final var stringFlag = ForkAiCapability.parse(deck("{\"forkAi\": {\"version\": 1, \"configured\": \"true\"}}"));
        assertNotNull(stringFlag);
        assertFalse(stringFlag.isConfigured());
        final var missingFlag = ForkAiCapability.parse(deck("{\"forkAi\": {\"version\": 1}}"));
        assertNotNull(missingFlag);
        assertFalse(missingFlag.isConfigured());
    }

    @Test
    public void capabilitiesResponse_configured() {
        final var capabilities = parseCapabilities("capabilities_configured.json");
        assertEquals("1.18.5", capabilities.getDeckVersion().getOriginalVersion());
        assertEquals(new ForkAiCapability(1, true), capabilities.getForkAi());
    }

    @Test
    public void capabilitiesResponse_notConfigured() {
        final var capabilities = parseCapabilities("capabilities_unconfigured.json");
        assertEquals(new ForkAiCapability(1, false), capabilities.getForkAi());
    }

    @Test
    public void capabilitiesResponse_stockServer() {
        final var capabilities = parseCapabilities("capabilities_stock.json");
        assertEquals("1.18.5", capabilities.getDeckVersion().getOriginalVersion());
        assertNull(capabilities.getForkAi());
    }

    @Test
    public void store_perAccount() {
        final var context = ApplicationProvider.getApplicationContext();

        ForkAiCapabilityStore.save(context, 1, parseCapabilities("capabilities_configured.json"));
        ForkAiCapabilityStore.save(context, 2, parseCapabilities("capabilities_unconfigured.json"));
        ForkAiCapabilityStore.save(context, 3, parseCapabilities("capabilities_stock.json"));

        assertEquals(new ForkAiCapability(1, true), ForkAiCapabilityStore.get(context, 1));
        assertEquals(new ForkAiCapability(1, false), ForkAiCapabilityStore.get(context, 2));
        assertNull(ForkAiCapabilityStore.get(context, 3));
        assertNull(ForkAiCapabilityStore.get(context, 4));
    }

    @Test
    public void store_serverLosesTheFeature() {
        final var context = ApplicationProvider.getApplicationContext();

        ForkAiCapabilityStore.save(context, 5, parseCapabilities("capabilities_configured.json"));
        ForkAiCapabilityStore.save(context, 5, parseCapabilities("capabilities_stock.json"));

        assertNull(ForkAiCapabilityStore.get(context, 5));
    }

    @Test
    public void store_ignoresMaintenanceMode() {
        final var context = ApplicationProvider.getApplicationContext();
        ForkAiCapabilityStore.save(context, 6, parseCapabilities("capabilities_configured.json"));

        final var maintenance = new Capabilities();
        maintenance.setMaintenanceEnabled(true);
        ForkAiCapabilityStore.save(context, 6, maintenance);
        ForkAiCapabilityStore.save(context, 6, null);

        assertEquals(new ForkAiCapability(1, true), ForkAiCapabilityStore.get(context, 6));
    }
}
