/*
 * SPDX-FileCopyrightText: 2026 splinteredlight
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package it.niedermann.nextcloud.deck.forkai;

import it.niedermann.nextcloud.deck.forkai.model.AiRunResult;
import it.niedermann.nextcloud.deck.forkai.model.AssistantRequest;
import it.niedermann.nextcloud.deck.forkai.model.AutoTagStatus;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.Headers;
import retrofit2.http.POST;
import retrofit2.http.Path;

/**
 * REST endpoints of the forked Deck server app (capability <code>deck.forkAi</code>).
 * Relative to <code>/index.php/apps/deck/api/</code>, like {@link it.niedermann.nextcloud.deck.remote.api.DeckAPI}.
 * <p>
 * The two POSTs are <strong>not idempotent</strong>: never retry them automatically.
 */
public interface ForkAiApi {

    String ENDPOINT = "/index.php/apps/deck/api/";

    @Headers("Accept: application/json")
    @POST("v1.0/boards/{boardId}/assistant")
    Call<AiRunResult> runAssistant(@Path("boardId") long remoteBoardId, @Body AssistantRequest request);

    @Headers("Accept: application/json")
    @GET("v1.0/boards/{boardId}/autotag")
    Call<AutoTagStatus> getAutoTagStatus(@Path("boardId") long remoteBoardId);

    @Headers("Accept: application/json")
    @POST("v1.0/boards/{boardId}/autotag")
    Call<AiRunResult> runAutoTag(@Path("boardId") long remoteBoardId);
}
