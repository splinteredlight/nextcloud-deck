package it.niedermann.nextcloud.deck.model.ocs;

import android.graphics.Color;

import androidx.annotation.ColorInt;

public class Capabilities {

    @ColorInt
    public static final int DEFAULT_COLOR = -16743735; // #0082C9;
    @ColorInt
    public static final int DEFAULT_TEXT_COLOR = Color.WHITE;

    private Version deckVersion;
    private Version nextcloudVersion;

    @ColorInt
    private int color = DEFAULT_COLOR;
    @ColorInt
    private int textColor = DEFAULT_TEXT_COLOR;
    private boolean maintenanceEnabled = false;
    // fork: AI endpoints, null on stock servers
    private it.niedermann.nextcloud.deck.forkai.ForkAiCapability forkAi;

    public Capabilities() {
    }

    public it.niedermann.nextcloud.deck.forkai.ForkAiCapability getForkAi() {
        return forkAi;
    }

    public void setForkAi(it.niedermann.nextcloud.deck.forkai.ForkAiCapability forkAi) {
        this.forkAi = forkAi;
    }

    public Version getDeckVersion() {
        return deckVersion;
    }

    public void setDeckVersion(Version deckVersion) {
        this.deckVersion = deckVersion;
    }

    public Version getNextcloudVersion() {
        return nextcloudVersion;
    }

    public void setNextcloudVersion(Version nextcloudVersion) {
        this.nextcloudVersion = nextcloudVersion;
    }

    @ColorInt
    public int getColor() {
        return color;
    }

    public void setColor(@ColorInt int color) {
        this.color = color;
    }

    @ColorInt
    public int getTextColor() {
        return textColor;
    }

    public void setTextColor(@ColorInt int textColor) {
        this.textColor = textColor;
    }

    public boolean isMaintenanceEnabled() {
        return maintenanceEnabled;
    }

    public void setMaintenanceEnabled(boolean maintenanceEnabled) {
        this.maintenanceEnabled = maintenanceEnabled;
    }
}
