package org.telegram.messenger;

import android.os.SystemClock;

import androidx.annotation.UiThread;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;

/**
 * Account-scoped runner for destructive dialog actions. It deliberately owns no Activity so
 * closing the dialogs screen cannot cancel an operation or keep the screen alive.
 */
public final class BulkDialogActionController extends BaseController {

    public static final int TYPE_LEAVE_CHANNELS = 0;
    public static final int TYPE_STOP_BOTS = 1;

    // Requests remain strictly single-flight. This small gap avoids a same-millisecond burst,
    // while FLOOD_WAIT remains the authoritative server-side rate limit.
    private static final long INTER_REQUEST_DELAY_MS = 350L;
    private static final long FLOOD_WAIT_SAFETY_MS = 250L;

    private static volatile BulkDialogActionController[] instances = new BulkDialogActionController[UserConfig.MAX_ACCOUNT_COUNT];

    public static BulkDialogActionController getInstance(int account) {
        BulkDialogActionController controller = instances[account];
        if (controller == null) {
            synchronized (BulkDialogActionController.class) {
                controller = instances[account];
                if (controller == null) {
                    instances[account] = controller = new BulkDialogActionController(account);
                }
            }
        }
        return controller;
    }

    private final ArrayDeque<Operation> pendingOperations = new ArrayDeque<>();
    private Operation currentOperation;

    private BulkDialogActionController(int account) {
        super(account);
    }

    @UiThread
    public void enqueue(ArrayList<Long> dialogIds, int type, boolean deleteBotChats) {
        if (dialogIds == null || dialogIds.isEmpty()
                || type != TYPE_LEAVE_CHANNELS && type != TYPE_STOP_BOTS) {
            return;
        }
        ArrayList<Long> uniqueDialogIds = new ArrayList<>(new LinkedHashSet<>(dialogIds));
        pendingOperations.add(new Operation(uniqueDialogIds, type, deleteBotChats));
        FileLog.d("DEBUG_HUNT bulk account=" + currentAccount + " event=enqueue type=" + type
                + " count=" + uniqueDialogIds.size() + " queued=" + pendingOperations.size());
        if (currentOperation == null) {
            startNextOperation();
        }
    }

    private void startNextOperation() {
        currentOperation = pendingOperations.poll();
        if (currentOperation == null) {
            return;
        }
        FileLog.d("DEBUG_HUNT bulk account=" + currentAccount + " event=start type=" + currentOperation.type
                + " count=" + currentOperation.dialogIds.size() + " delete=" + currentOperation.deleteBotChats);
        sendCurrentStep();
    }

    private void sendCurrentStep() {
        Operation operation = currentOperation;
        if (operation == null) {
            return;
        }
        operation.scheduledRunnable = null;
        if (operation.index >= operation.dialogIds.size()) {
            finishCurrentOperation();
        } else if (operation.type == TYPE_LEAVE_CHANNELS) {
            sendLeaveChannel(operation, operation.dialogIds.get(operation.index));
        } else if (operation.botStep == 0) {
            sendStopBot(operation, operation.dialogIds.get(operation.index));
        } else {
            sendDeleteBotHistory(operation, operation.dialogIds.get(operation.index));
        }
    }

    private void sendLeaveChannel(Operation operation, long dialogId) {
        TLRPC.Chat chat = getMessagesController().getChat(-dialogId);
        if (chat == null || !ChatObject.isChannel(chat) || chat.megagroup || chat.creator || chat.left) {
            failCurrent(operation, "CHANNEL_NOT_AVAILABLE", false);
            return;
        }
        TLRPC.InputChannel inputChannel = getMessagesController().getInputChannel(chat);
        if (inputChannel == null || inputChannel instanceof TLRPC.TL_inputChannelEmpty) {
            failCurrent(operation, "CHANNEL_NOT_AVAILABLE", false);
            return;
        }

        TLRPC.TL_channels_leaveChannel request = new TLRPC.TL_channels_leaveChannel();
        request.channel = inputChannel;
        markRequestStarted(operation, "leave");
        getConnectionsManager().sendRequest(request, (response, error) -> {
            if (error == null && response instanceof TLRPC.Updates) {
                getMessagesController().processUpdates((TLRPC.Updates) response, false);
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (!acceptResponse(operation, error)) {
                    return;
                }
                if (error != null) {
                    failCurrent(operation, error.text, true);
                    return;
                }
                chat.left = true;
                getMessagesController().deleteDialogLocally(dialogId);
                completeCurrent(operation);
            });
        }, ConnectionsManager.RequestFlagInvokeAfter);
    }

    private void sendStopBot(Operation operation, long dialogId) {
        TLRPC.User user = getMessagesController().getUser(dialogId);
        if (user == null || !user.bot || MessagesController.isSupportUser(user)) {
            failCurrent(operation, "BOT_NOT_AVAILABLE", false);
            return;
        }
        TLRPC.InputPeer inputPeer = getMessagesController().getInputPeer(user);
        if (inputPeer == null || inputPeer instanceof TLRPC.TL_inputPeerEmpty) {
            failCurrent(operation, "BOT_NOT_AVAILABLE", false);
            return;
        }

        TLRPC.TL_contacts_block request = new TLRPC.TL_contacts_block();
        request.id = inputPeer;
        markRequestStarted(operation, "stop");
        getConnectionsManager().sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (!acceptResponse(operation, error)) {
                return;
            }
            if (error != null) {
                failCurrent(operation, error.text, true);
                return;
            }
            getMessagesController().markPeerBlocked(dialogId);
            if (operation.deleteBotChats) {
                operation.botStep = 1;
                scheduleNext(operation, INTER_REQUEST_DELAY_MS);
            } else {
                completeCurrent(operation);
            }
        }), ConnectionsManager.RequestFlagInvokeAfter);
    }

    private void sendDeleteBotHistory(Operation operation, long dialogId) {
        TLRPC.InputPeer peer = getMessagesController().getInputPeer(dialogId);
        if (peer == null || peer instanceof TLRPC.TL_inputPeerEmpty) {
            failCurrent(operation, "BOT_NOT_AVAILABLE", false);
            return;
        }

        TLRPC.TL_messages_deleteHistory request = new TLRPC.TL_messages_deleteHistory();
        request.peer = peer;
        request.max_id = Integer.MAX_VALUE;
        request.just_clear = false;
        request.revoke = false;
        markRequestStarted(operation, "delete");
        getConnectionsManager().sendRequest(request, (response, error) -> {
            if (error == null && response instanceof TLRPC.TL_messages_affectedHistory) {
                TLRPC.TL_messages_affectedHistory result = (TLRPC.TL_messages_affectedHistory) response;
                getMessagesController().processNewDifferenceParams(-1, result.pts, -1, result.pts_count);
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (!acceptResponse(operation, error)) {
                    return;
                }
                if (error != null || !(response instanceof TLRPC.TL_messages_affectedHistory)) {
                    failCurrent(operation, error != null ? error.text : "DELETE_HISTORY_FAILED", true);
                    return;
                }
                TLRPC.TL_messages_affectedHistory result = (TLRPC.TL_messages_affectedHistory) response;
                if (result.offset > 0) {
                    scheduleNext(operation, INTER_REQUEST_DELAY_MS);
                    return;
                }
                getMessagesController().deleteDialogLocally(dialogId);
                completeCurrent(operation);
            });
        }, ConnectionsManager.RequestFlagInvokeAfter);
    }

    private void markRequestStarted(Operation operation, String step) {
        operation.requestStep = step;
        operation.requestStartedAt = SystemClock.elapsedRealtime();
        FileLog.d("DEBUG_HUNT bulk account=" + currentAccount + " event=request type=" + operation.type
                + " step=" + step + " index=" + (operation.index + 1) + "/" + operation.dialogIds.size());
    }

    private boolean acceptResponse(Operation operation, TLRPC.TL_error error) {
        if (currentOperation != operation) {
            return false;
        }
        long elapsed = SystemClock.elapsedRealtime() - operation.requestStartedAt;
        FileLog.d("DEBUG_HUNT bulk account=" + currentAccount + " event=response type=" + operation.type
                + " step=" + operation.requestStep + " index=" + (operation.index + 1) + "/" + operation.dialogIds.size()
                + " elapsed_ms=" + elapsed + " error=" + (error == null ? "none" : error.text));
        int floodWaitSeconds = parseFloodWaitSeconds(error);
        if (floodWaitSeconds > 0) {
            FileLog.d("DEBUG_HUNT bulk account=" + currentAccount + " event=flood_wait seconds=" + floodWaitSeconds
                    + " index=" + (operation.index + 1) + "/" + operation.dialogIds.size());
            scheduleNext(operation, floodWaitSeconds * 1_000L + FLOOD_WAIT_SAFETY_MS);
            return false;
        }
        return true;
    }

    private static int parseFloodWaitSeconds(TLRPC.TL_error error) {
        if (error == null || error.text == null || !error.text.contains("FLOOD_WAIT")) {
            return 0;
        }
        int separator = error.text.lastIndexOf('_');
        if (separator < 0 || separator + 1 >= error.text.length()) {
            return 0;
        }
        try {
            return Math.max(1, Integer.parseInt(error.text.substring(separator + 1)));
        } catch (NumberFormatException ignore) {
            return 0;
        }
    }

    private void completeCurrent(Operation operation) {
        operation.succeeded++;
        operation.index++;
        operation.botStep = 0;
        advance(operation, true);
    }

    private void failCurrent(Operation operation, String error, boolean requestWasSent) {
        operation.failed++;
        operation.lastError = error;
        operation.index++;
        operation.botStep = 0;
        advance(operation, requestWasSent);
    }

    private void advance(Operation operation, boolean requestWasSent) {
        if (operation.index >= operation.dialogIds.size()) {
            finishCurrentOperation();
        } else if (requestWasSent) {
            scheduleNext(operation, INTER_REQUEST_DELAY_MS);
        } else {
            // Invalid or stale cached peers can occur in large selections. Yield to the UI queue
            // instead of recursing through all of them on the main thread.
            scheduleNext(operation, 0);
        }
    }

    private void scheduleNext(Operation operation, long delayMs) {
        if (currentOperation != operation) {
            return;
        }
        if (operation.scheduledRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(operation.scheduledRunnable);
        }
        operation.scheduledRunnable = this::sendCurrentStep;
        AndroidUtilities.runOnUIThread(operation.scheduledRunnable, delayMs);
    }

    private void finishCurrentOperation() {
        Operation operation = currentOperation;
        if (operation == null) {
            return;
        }
        FileLog.d("DEBUG_HUNT bulk account=" + currentAccount + " event=finish type=" + operation.type
                + " succeeded=" + operation.succeeded + " failed=" + operation.failed + " total=" + operation.dialogIds.size());
        getNotificationCenter().postNotificationName(
                NotificationCenter.bulkDialogActionFinished,
                operation.type,
                operation.succeeded,
                operation.dialogIds.size(),
                operation.failed,
                operation.lastError
        );
        currentOperation = null;
        startNextOperation();
    }

    private static final class Operation {
        private final ArrayList<Long> dialogIds;
        private final int type;
        private final boolean deleteBotChats;
        private int index;
        private int succeeded;
        private int failed;
        private int botStep;
        private long requestStartedAt;
        private String requestStep;
        private String lastError;
        private Runnable scheduledRunnable;

        private Operation(ArrayList<Long> dialogIds, int type, boolean deleteBotChats) {
            this.dialogIds = dialogIds;
            this.type = type;
            this.deleteBotChats = deleteBotChats;
        }
    }
}
