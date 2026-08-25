package tw.nekomimi.nekogram.helpers;

import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * Pulls down the media of messages that are about to be re-sent as copies. A chat that forbids
 * forwarding still lets us send our own message, but only if the file is on disk first — the
 * server will not hand it to the target chat for us.
 */
public class ForwardMediaLoader implements NotificationCenter.NotificationCenterDelegate {

    public interface Callback {
        void onProgress(int done, int total);

        void onFinished(boolean complete);
    }

    private final int currentAccount;
    private final Callback callback;
    private final HashSet<String> pending = new HashSet<>();
    private final ArrayList<MessageObject> messages = new ArrayList<>();
    private int total;
    private boolean finished;

    public ForwardMediaLoader(int currentAccount, Callback callback) {
        this.currentAccount = currentAccount;
        this.callback = callback;
    }

    /** Messages carrying media that is not on disk yet. */
    public static ArrayList<MessageObject> missing(ArrayList<MessageObject> messages) {
        ArrayList<MessageObject> out = new ArrayList<>();
        if (messages == null) {
            return out;
        }
        for (MessageObject message : messages) {
            if (carriesMedia(message) && TextUtils.isEmpty(MessageHelper.getPathToMessage(message))) {
                out.add(message);
            }
        }
        return out;
    }

    private static boolean carriesMedia(MessageObject message) {
        if (message == null || message.messageOwner == null) {
            return false;
        }
        if (message.isSticker() || message.isAnimatedSticker() || message.isAnimatedEmoji()) {
            return false;
        }
        return message.isPhoto() || message.isVideo() || message.isRoundVideo() || message.getDocument() != null;
    }

    public void start(ArrayList<MessageObject> targets) {
        messages.addAll(targets);
        total = targets.size();
        for (MessageObject message : targets) {
            String name = fileName(message);
            if (name != null) {
                pending.add(name);
            }
        }
        if (pending.isEmpty()) {
            finish(true);
            return;
        }
        // Observers first: a file already in flight can finish before the last load is queued.
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.fileLoaded);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.fileLoadFailed);
        callback.onProgress(total - pending.size(), total);
        for (MessageObject message : targets) {
            load(message);
        }
    }

    /** The name the load will be reported under. */
    private String fileName(MessageObject message) {
        TLRPC.Document document = message.getDocument();
        if (document != null) {
            return FileLoader.getAttachFileName(document);
        }
        TLRPC.PhotoSize photoSize = photoSize(message);
        return photoSize == null ? null : FileLoader.getAttachFileName(photoSize);
    }

    private void load(MessageObject message) {
        TLRPC.Document document = message.getDocument();
        if (document != null) {
            FileLoader.getInstance(currentAccount).loadFile(document, message, FileLoader.PRIORITY_NORMAL, 0);
            return;
        }
        TLRPC.PhotoSize photoSize = photoSize(message);
        if (photoSize != null) {
            FileLoader.getInstance(currentAccount).loadFile(
                    ImageLocation.getForObject(photoSize, message.photoThumbsObject),
                    message, null, FileLoader.PRIORITY_NORMAL, 0);
        }
    }

    private static TLRPC.PhotoSize photoSize(MessageObject message) {
        return FileLoader.getClosestPhotoSizeWithSize(message.photoThumbs, AndroidUtilities.getPhotoSize());
    }

    public void cancel() {
        if (finished) {
            return;
        }
        for (MessageObject message : messages) {
            TLRPC.Document document = message.getDocument();
            if (document != null) {
                FileLoader.getInstance(currentAccount).cancelLoadFile(document);
            } else {
                TLRPC.PhotoSize photoSize = photoSize(message);
                if (photoSize != null) {
                    FileLoader.getInstance(currentAccount).cancelLoadFile(photoSize);
                }
            }
        }
        finish(false);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (finished || args.length == 0 || !(args[0] instanceof String)) {
            return;
        }
        if (!pending.remove((String) args[0])) {
            return;
        }
        if (id == NotificationCenter.fileLoadFailed) {
            finish(false);
            return;
        }
        callback.onProgress(total - pending.size(), total);
        if (pending.isEmpty()) {
            finish(true);
        }
    }

    private void finish(boolean complete) {
        if (finished) {
            return;
        }
        finished = true;
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.fileLoaded);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.fileLoadFailed);
        callback.onFinished(complete);
    }
}
