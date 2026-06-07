package soloMapling.server.EventMessageSystem;

public enum EventType {
    GACHAPON_REWARD("Gachapon Reward"),
    LEVEL_UP("Level Up"),
    SCROLLING("Scrolling Result"),
    ITEM_MEGAPHONE("Item Megaphone Announcement"),
    CHAT_MEGAPHONE("Chat Megaphone"),
    MASTERY_RESULT("Mastery Result"),
    EMOTE("Emote"),
    CHAT_GENERAL("General Chat"),
    MAP_ENTERED("Player Entered Map");

    private final String description;

    EventType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
