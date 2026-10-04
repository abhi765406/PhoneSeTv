package com.homewatch.app;

public class Config {
    public static final String PREFS = "homewatch_prefs";

    // EDIT THESE TWO AFTER YOU DEPLOY YOUR OWN SIGNALING SERVER (see signaling-server/README.md).
    // Both must point at the same Render service - just swap the scheme.
    public static final String SIGNALING_WS_URL = "https://homewatch-signaling.onrender.com";
    public static final String ICE_SERVERS_URL = "https://homewatch-signaling.onrender.com";
}
