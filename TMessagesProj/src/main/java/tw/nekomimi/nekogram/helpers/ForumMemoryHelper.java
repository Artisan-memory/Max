package tw.nekomimi.nekogram.helpers;

import android.content.SharedPreferences;

import java.util.concurrent.ConcurrentHashMap;

import tw.nekomimi.nekogram.NekoConfig;

/**
 * channelForbidden has no forum flag in the schema, so getting kicked or banned from a forum
 * turns it back into a plain group and hides the topics we still have history in. Remember the
 * flag while the chat is reachable and restore it for as long as the chat stays forbidden.
 */
public class ForumMemoryHelper {
    private static final String forumPrefix = "knownForum_";
    private static final String forumTabsPrefix = "knownForumTabs_";

    private static final ConcurrentHashMap<Long, Boolean> forumCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Boolean> forumTabsCache = new ConcurrentHashMap<>();

    private static SharedPreferences getPreferences() {
        return NekoConfig.getPreferences();
    }

    public static boolean isKnownForum(long chatId) {
        return forumCache.computeIfAbsent(chatId, id -> getPreferences().getBoolean(forumPrefix + id, false));
    }

    public static boolean hasKnownForumTabs(long chatId) {
        return forumTabsCache.computeIfAbsent(chatId, id -> getPreferences().getBoolean(forumTabsPrefix + id, false));
    }

    public static void remember(long chatId, boolean forum, boolean forumTabs) {
        if (isKnownForum(chatId) == forum && (!forum || hasKnownForumTabs(chatId) == forumTabs)) {
            return;
        }
        forumCache.put(chatId, forum);
        forumTabsCache.put(chatId, forum && forumTabs);
        SharedPreferences.Editor editor = getPreferences().edit();
        if (forum) {
            editor.putBoolean(forumPrefix + chatId, true).putBoolean(forumTabsPrefix + chatId, forumTabs);
        } else {
            editor.remove(forumPrefix + chatId).remove(forumTabsPrefix + chatId);
        }
        editor.apply();
    }
}
