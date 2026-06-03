package com.radolyn.ayugram.utils;

import android.content.res.AssetFileDescriptor;
import android.net.Uri;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.tgnet.tl.TL_stories;

import tw.nekomimi.nekogram.NekoConfig;

import java.io.File;
import java.util.List;

public class AyuGhostUtils {

    private static final int OFFLINE_DELAY_MS = 1000;
    private static final int DEFAULT_SCHEDULE_DELAY_SECONDS = 12;
    private static final int VOICE_SCHEDULE_DELAY_SECONDS = 17;

    public static Long getDialogId(TLRPC.InputPeer peer) {
        long dialogId;
        if (peer.chat_id != 0) {
            dialogId = -peer.chat_id;
        } else if (peer.channel_id != 0) {
            dialogId = -peer.channel_id;
        } else {
            dialogId = peer.user_id;
        }

        return dialogId;
    }

    public static Long getDialogId(TLRPC.InputChannel peer) {
        return -peer.channel_id;
    }

    public static Long getDialogId(TLRPC.TL_inputEncryptedChat peer) {
        if (peer == null) {
            return null;
        }
        return (long) DialogObject.getEncryptedChatId(peer.chat_id);
    }

    public static ConnectionsManager getConnectionsManager() {
        return ConnectionsManager.getInstance(UserConfig.selectedAccount);
    }

    public static ConnectionsManager getConnectionsManager(int account) {
        return ConnectionsManager.getInstance(account);
    }

    public static MessagesController getMessagesController() {
        return MessagesController.getInstance(UserConfig.selectedAccount);
    }

    public static MessagesController getMessagesController(int account) {
        return MessagesController.getInstance(account);
    }

    public static MessagesStorage getMessagesStorage() {
        return MessagesStorage.getInstance(UserConfig.selectedAccount);
    }

    public static MessagesStorage getMessagesStorage(int account) {
        return MessagesStorage.getInstance(account);
    }

    public static void applyGhostSchedule(SendMessagesHelper.SendMessageParams options, int nowSeconds, int account) {
        if (options.scheduleDate != 0 || options.ghostScheduled || DialogObject.isEncryptedDialog(options.peer)) {
            return;
        }

        int delay = DEFAULT_SCHEDULE_DELAY_SECONDS;
        if (options.document != null) {
            if (MessageObject.isVoiceDocument(options.document) || MessageObject.isRoundVideoDocument(options.document)) {
                delay = VOICE_SCHEDULE_DELAY_SECONDS;
            } else if (!isAlbum(options) && options.document.id == 0) {
                delay = getUploadDelaySeconds(getFileSize(options.path));
            }
        }

        int scheduleDate = applyGhostScheduleDate(options.scheduleDate, nowSeconds, delay, account);
        if (scheduleDate != options.scheduleDate) {
            options.scheduleDate = scheduleDate;
            options.ghostScheduled = true;
        }
    }

    public static int applyGhostScheduleDate(int scheduleDate, int nowSeconds, int delaySeconds, int account) {
        if (scheduleDate != 0 || !NekoConfig.isGhostModeActive(account) || !NekoConfig.isScheduleMessagesEnabled(account)) {
            return scheduleDate;
        }
        return nowSeconds + adjustScheduleDelay(delaySeconds);
    }

    public static int getUploadDelaySeconds(long totalBytes) {
        long unit = 1024L * 1024L * 10L;
        long uploadDelay = (totalBytes * 7 + unit - 1) / unit;
        return 13 + (int) Math.max(6, uploadDelay);
    }

    public static long getTotalFileSize(Iterable<String> paths) {
        long totalBytes = 0;
        if (paths != null) {
            for (String path : paths) {
                totalBytes += getFileSize(path);
            }
        }
        return totalBytes;
    }

    public static long getTotalFileSize(Iterable<String> paths, Iterable<Uri> uris) {
        long totalBytes = getTotalFileSize(paths);
        if (uris != null) {
            for (Uri uri : uris) {
                totalBytes += getFileSize(uri);
            }
        }
        return totalBytes;
    }

    public static long getTotalFileSizeForAlternatives(List<String> paths, List<Uri> uris) {
        long totalBytes = 0;
        int count = Math.max(paths != null ? paths.size() : 0, uris != null ? uris.size() : 0);
        for (int i = 0; i < count; i++) {
            long fileSize = paths != null && i < paths.size() ? getFileSize(paths.get(i)) : 0;
            if (fileSize == 0 && uris != null && i < uris.size()) {
                fileSize = getFileSize(uris.get(i));
            }
            totalBytes += fileSize;
        }
        return totalBytes;
    }

    private static int adjustScheduleDelay(int delaySeconds) {
        return SharedConfig.isProxyEnabled() ? (delaySeconds * 6 + 4) / 5 : delaySeconds;
    }

    private static boolean isAlbum(SendMessagesHelper.SendMessageParams options) {
        return options.params != null && options.params.containsKey("groupId");
    }

    private static long getFileSize(String path) {
        if (path == null) {
            return 0;
        }
        File file = new File(path);
        return file.exists() ? file.length() : 0;
    }

    private static long getFileSize(Uri uri) {
        if (uri == null) {
            return 0;
        }
        try (AssetFileDescriptor descriptor = ApplicationLoader.applicationContext.getContentResolver().openAssetFileDescriptor(uri, "r")) {
            return descriptor != null && descriptor.getLength() > 0 ? descriptor.getLength() : 0;
        } catch (Exception e) {
            FileLog.e(e);
            return 0;
        }
    }

    public static void markReadOnServer(int messageId, TLRPC.InputPeer peer, boolean internal) {
        markReadOnServer(messageId, peer, internal, UserConfig.selectedAccount);
    }

    public static void markReadOnServer(int messageId, TLRPC.InputPeer peer, boolean internal, int account) {
        TLObject req;
        if (peer instanceof TLRPC.TL_inputPeerChannel) {
            TLRPC.TL_channels_readHistory request = new TLRPC.TL_channels_readHistory();
            request.channel = MessagesController.getInputChannel(peer);
            request.max_id = messageId;
            req = request;
        } else {
            TLRPC.TL_messages_readHistory request = new TLRPC.TL_messages_readHistory();
            request.peer = peer;
            request.max_id = messageId;
            req = request;
        }

        AyuState.setAllowReadPacket(true, 1);
        getConnectionsManager(account).sendRequest(req, (response, error) -> {
            if (error == null) {
                if (response instanceof TLRPC.TL_messages_affectedMessages res) {
                    getMessagesController(account).processNewDifferenceParams(-1, res.pts, -1, res.pts_count);
                }
                if (internal) FileLog.d("GhostMode: Read-after-send request completed.");
                // Go offline after sending
                if (NekoConfig.sendOfflinePacketAfterOnline.Bool() && !internal) {
                    Utilities.globalQueue.postRunnable(() -> performStatusRequest(true, account), OFFLINE_DELAY_MS);
                }
            }
        });
    }

    public static void markReadOnServer(MessageObject message, boolean internal) {
        int account = message.currentAccount;
        int messageId = message.getId();
        long dialogId = message.getDialogId();
        TLRPC.EncryptedChat encryptedChat = getMessagesController(account).getEncryptedChat(DialogObject.getEncryptedChatId(dialogId));
        TLRPC.InputPeer inputPeer = getMessagesController(account).getInputPeer(message.messageOwner.peer_id);
        TLObject req;
        if (inputPeer instanceof TLRPC.TL_inputPeerChannel) {
            TLRPC.TL_channels_readHistory request = new TLRPC.TL_channels_readHistory();
            request.channel = MessagesController.getInputChannel(inputPeer);
            request.max_id = messageId;
            req = request;
        } else if (encryptedChat != null) {
            TLRPC.TL_messages_readEncryptedHistory request = new TLRPC.TL_messages_readEncryptedHistory();
            request.peer = new TLRPC.TL_inputEncryptedChat();
            request.peer.chat_id = encryptedChat.id;
            request.peer.access_hash = encryptedChat.access_hash;
            request.max_date = message.messageOwner.date != 0 ? message.messageOwner.date : getConnectionsManager(account).getCurrentTime();
            req = request;
        } else {
            TLRPC.TL_messages_readHistory request = new TLRPC.TL_messages_readHistory();
            request.peer = inputPeer;
            request.max_id = messageId;
            req = request;
        }

        AyuState.setAllowReadPacket(true, 1);
        getConnectionsManager(account).sendRequest(req, (response, error) -> {
            if (error == null) {
                if (response instanceof TLRPC.TL_messages_affectedMessages res) {
                    getMessagesController(account).processNewDifferenceParams(-1, res.pts, -1, res.pts_count);
                }
                if (internal) FileLog.d("GhostMode: Read-after-send request completed.");
                // Go offline after sending
                if (NekoConfig.sendOfflinePacketAfterOnline.Bool() && !internal) {
                    Utilities.globalQueue.postRunnable(() -> performStatusRequest(true, account), OFFLINE_DELAY_MS);
                }
            }
        });
    }

    public static void performStatusRequest(Boolean offline) {
        performStatusRequest(offline, UserConfig.selectedAccount);
    }

    public static void performStatusRequest(Boolean offline, int account) {
        TL_account.updateStatus offlineRequest = new TL_account.updateStatus();
        offlineRequest.offline = offline;

        getConnectionsManager(account).sendRequest(offlineRequest, (response, error) -> FileLog.d("GhostMode: Status request completed."));
    }

    public static void prepareAccountSwitch(int account) {
        if (NekoConfig.isGhostModeActive(account)) {
            performStatusRequest(true, account);
        }
    }

    public static InterceptResult interceptRequest(int account, TLObject object, RequestDelegate onCompleteOrig) {
        if (!NekoConfig.isGhostModeActive(account)) {
            return InterceptResult.Proceed(onCompleteOrig);
        }
        Long dialogId = extractDialogId(object);
        boolean readExcluded = dialogId != null && AyuGhostPreferences.getGhostModeReadExclusion(dialogId);
        boolean typingExcluded = dialogId != null && AyuGhostPreferences.getGhostModeTypingExclusion(dialogId);

        // Block typing if disabled
        if (!NekoConfig.sendUploadProgress.Bool() && (object instanceof TLRPC.TL_messages_setTyping || object instanceof TLRPC.TL_messages_setEncryptedTyping)) {
            if (!typingExcluded) {
                FileLog.d("GhostMode: Blocking typing status request.");
                return InterceptResult.Blocked(onCompleteOrig);
            }
        }

        // Block read receipts if disabled
        if (!NekoConfig.sendReadMessagePackets.Bool() && (isReadMessageRequest(object))) {
            if (!AyuState.getAllowReadPacket() && !readExcluded) {
                FileLog.d("GhostMode: Blocking read status request and sending fake response.");
                sendFakeReadResponse(onCompleteOrig);
                return InterceptResult.Blocked(onCompleteOrig);
            }
        }
        if (!NekoConfig.sendReadStoriesPackets.Bool() && isReadStoriesRequest(object)) {
            if (!readExcluded) {
                FileLog.d("GhostMode: Blocking story read request.");
                return InterceptResult.Blocked(onCompleteOrig);
            }
        }

        // Force offline if online status sending disabled
        if (!NekoConfig.sendOnlinePackets.Bool() && object instanceof TL_account.updateStatus updateStatus) {
            FileLog.d("GhostMode: Forcing offline status in updateStatus request.");
            updateStatus.offline = true;
        }

        // Handle Mark read after sending
        handleReadAfterSend(account, object);

        // Go offline after sending
        RequestDelegate effectiveOnComplete = handleOfflineAfterSend(account, object, onCompleteOrig);

        return InterceptResult.Proceed(effectiveOnComplete);
    }

    private static void handleReadAfterSend(int account, TLObject object) {
        if (NekoConfig.markReadAfterSend.Bool() && !NekoConfig.sendReadMessagePackets.Bool()) {
            TLRPC.InputPeer peer = extractPeerFromSendObject(object);

            if (peer != null) {
                var dialogId = AyuGhostUtils.getDialogId(peer);
                if (AyuGhostPreferences.getGhostModeReadExclusion(dialogId)) {
                    return;
                }
                getMessagesStorage(account).getStorageQueue().postRunnable(() ->
                    getMessagesStorage(account).getDialogMaxMessageId(dialogId, maxId ->
                        markReadOnServer(maxId, peer, true, account)
                    )
                );
            }
        }
    }

    private static RequestDelegate handleOfflineAfterSend(int account, TLObject object, RequestDelegate onCompleteOrig) {
        if (NekoConfig.sendOfflinePacketAfterOnline.Bool() && isMessageSendRequest(object)) {
            TLRPC.InputPeer peer = extractPeerFromSendObject(object);
            if (peer != null && AyuGhostPreferences.getGhostModeTypingExclusion(getDialogId(peer))) {
                return onCompleteOrig;
            }
            FileLog.d("GhostMode: Wrapping callback for offline-after-send.");

            return (response, error) -> {
                if (onCompleteOrig != null) {
                    Utilities.stageQueue.postRunnable(() -> onCompleteOrig.run(response, error));
                }

                FileLog.d("GhostMode: Scheduling delayed offline status update.");
                Utilities.globalQueue.postRunnable(() -> performStatusRequest(true, account), OFFLINE_DELAY_MS);
            };
        }
        return onCompleteOrig;
    }

    private static Long extractDialogId(TLObject object) {
        if (object instanceof TLRPC.TL_messages_setTyping obj) {
            return getDialogId(obj.peer);
        } else if (object instanceof TLRPC.TL_messages_setEncryptedTyping obj) {
            return getDialogId(obj.peer);
        } else if (object instanceof TLRPC.TL_messages_readHistory obj) {
            return getDialogId(obj.peer);
        } else if (object instanceof TLRPC.TL_messages_readEncryptedHistory obj) {
            return getDialogId(obj.peer);
        } else if (object instanceof TLRPC.TL_messages_readDiscussion obj) {
            return getDialogId(obj.peer);
        } else if (object instanceof TLRPC.TL_messages_sendMessage obj) {
            return getDialogId(obj.peer);
        } else if (object instanceof TLRPC.TL_messages_sendMedia obj) {
            return getDialogId(obj.peer);
        } else if (object instanceof TLRPC.TL_messages_sendMultiMedia obj) {
            return getDialogId(obj.peer);
        } else if (object instanceof TL_stories.TL_stories_readStories obj) {
            return getDialogId(obj.peer);
        } else if (object instanceof TL_stories.TL_stories_incrementStoryViews obj) {
            return getDialogId(obj.peer);
        } else if (object instanceof TLRPC.TL_channels_readHistory obj) {
            return getDialogId(obj.channel);
        }
        return null;
    }

    private static void sendFakeReadResponse(RequestDelegate onCompleteOrig) {
        var fakeRes = new TLRPC.TL_messages_affectedMessages();
        fakeRes.pts = -1;
        fakeRes.pts_count = 0;
        Utilities.stageQueue.postRunnable(() -> {
            try {
                if (onCompleteOrig != null) {
                    onCompleteOrig.run(fakeRes, null);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    private static TLRPC.InputPeer extractPeerFromSendObject(TLObject object) {
        if (object instanceof TLRPC.TL_messages_sendMessage) {
            return ((TLRPC.TL_messages_sendMessage) object).peer;
        } else if (object instanceof TLRPC.TL_messages_sendMedia) {
            return ((TLRPC.TL_messages_sendMedia) object).peer;
        } else if (object instanceof TLRPC.TL_messages_sendMultiMedia) {
            return ((TLRPC.TL_messages_sendMultiMedia) object).peer;
        }
        return null;
    }

    private static boolean isReadMessageRequest(TLObject object) {
        return object instanceof TLRPC.TL_messages_readHistory ||
                object instanceof TLRPC.TL_messages_readEncryptedHistory ||
                object instanceof TLRPC.TL_messages_readDiscussion ||
                object instanceof TLRPC.TL_messages_readMessageContents ||
                object instanceof TLRPC.TL_channels_readHistory;
    }

    private static boolean isReadStoriesRequest(TLObject object) {
        return object instanceof TL_stories.TL_stories_readStories ||
                object instanceof TL_stories.TL_stories_incrementStoryViews;
    }

    private static boolean isMessageSendRequest(TLObject object) {
        return object instanceof TLRPC.TL_messages_sendMessage ||
                object instanceof TLRPC.TL_messages_sendMedia ||
                object instanceof TLRPC.TL_messages_sendMultiMedia;
    }

    public record InterceptResult(boolean blockRequest, RequestDelegate effectiveOnComplete) {

        public static InterceptResult Blocked(RequestDelegate originalOnComplete) {
                return new InterceptResult(true, originalOnComplete);
            }

            public static InterceptResult Proceed(RequestDelegate effectiveOnComplete) {
                return new InterceptResult(false, effectiveOnComplete);
            }
        }
}
