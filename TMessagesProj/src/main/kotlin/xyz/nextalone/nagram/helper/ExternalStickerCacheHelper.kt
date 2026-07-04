package xyz.nextalone.nagram.helper

import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.MediaDataController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.NotificationCenter.NotificationCenterDelegate
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.TLRPC.TL_messages_stickerSet
import tw.nekomimi.nekogram.utils.AlertUtil
import xyz.nextalone.nagram.NaConfig
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

object ExternalStickerCacheHelper {
    private const val MANAGED_DIRECTORY = "NagramStickerCache"
    private const val AUTO_SYNC_DELAY_MS = 1200L
    private const val PREFETCH_DELAY_MS = 20L
    private val STICKER_TYPES = intArrayOf(MediaDataController.TYPE_IMAGE, MediaDataController.TYPE_MASK)
    private val EMOJI_TYPES = intArrayOf(MediaDataController.TYPE_EMOJIPACKS, MediaDataController.TYPE_EMOJI)
    private val ALL_TYPES = STICKER_TYPES + EMOJI_TYPES

    data class CacheStats(val totalBytes: Long, val cachedCount: Int, val totalCount: Int)

    fun interface StatsListener {
        fun onCacheStatsChanged(stats: CacheStats)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()
    private val observedAccounts = ConcurrentHashMap.newKeySet<Int>()
    private val cachedDocuments = ConcurrentHashMap<Long, DocumentFile>()
    private val restoringDocuments = ConcurrentHashMap.newKeySet<String>()
    private val statsListeners = CopyOnWriteArrayList<StatsListener>()
    private var autoSyncJob: Job? = null
    private var statsJob: Job? = null

    @JvmStatic
    fun getDisplayPath(): String {
        val uri = NaConfig.externalStickerCacheUri ?: return ""
        return try {
            val documentId = Uri.decode(DocumentsContract.getTreeDocumentId(uri))
            val path = documentId.substringAfter(':', documentId).trimEnd('/')
            if (path.isBlank()) MANAGED_DIRECTORY else "$path/$MANAGED_DIRECTORY"
        } catch (_: Exception) {
            "${Uri.decode(uri.lastPathSegment ?: uri.toString()).trimEnd('/')}/$MANAGED_DIRECTORY"
        }
    }

    @JvmStatic
    fun onFolderSelected() {
        scope.launch {
            if (getManagedRoot(create = true) == null) {
                showToast(getString(R.string.ExternalStickerCacheFolderError))
            } else {
                rebuildIndex()
                cacheInstalledStickers(UserConfig.selectedAccount, ALL_TYPES, prefetchMissing = false, publishProgress = false)
                scheduleStatsRefresh()
                showToast(getString(R.string.ExternalStickerCacheFolderReady))
            }
        }
    }

    @JvmStatic
    fun syncAllCaches() {
        syncCaches(ALL_TYPES, R.string.ExternalStickerCacheSyncStarted, R.string.ExternalStickerCacheSyncQueued)
    }

    @JvmStatic
    fun syncAllStickerCaches() {
        syncCaches(STICKER_TYPES, R.string.ExternalStickerCacheSyncStarted, R.string.ExternalStickerCacheSyncQueued)
    }

    @JvmStatic
    fun syncAllEmojiCaches() {
        syncCaches(EMOJI_TYPES, R.string.ExternalEmojiCacheSyncStarted, R.string.ExternalEmojiCacheSyncQueued)
    }

    private fun syncCaches(types: IntArray, startString: Int, queuedString: Int) {
        if (NaConfig.externalStickerCacheUri == null) {
            showToast(getString(R.string.ExternalStickerCacheFolderRequired))
            return
        }
        showToast(getString(startString))
        val account = UserConfig.selectedAccount
        loadStickerSets(account, types) {
            scope.launch {
                try {
                    val result = cacheInstalledStickers(account, types, prefetchMissing = true, publishProgress = true)
                    scheduleStatsRefresh()
                    showToast(
                        if (result.queued > 0) {
                            getString(queuedString)
                        } else {
                            getString(R.string.Done)
                        }
                    )
                } catch (e: Exception) {
                    FileLog.e(e)
                    showToast(getString(R.string.ExternalStickerCacheFolderError))
                }
            }
        }
    }

    @JvmStatic
    fun deleteAllCaches() {
        if (NaConfig.externalStickerCacheUri == null) {
            showToast(getString(R.string.ExternalStickerCacheFolderRequired))
            return
        }
        scope.launch {
            try {
                syncMutex.withLock {
                    getManagedRoot(create = false)?.delete()
                    cachedDocuments.clear()
                }
                scheduleStatsRefresh()
                showToast(getString(R.string.Done))
            } catch (e: Exception) {
                FileLog.e(e)
                showToast(getString(R.string.ExternalStickerCacheFolderError))
            }
        }
    }

    @JvmStatic
    fun restoreBeforeDownload(account: Int, sticker: TLRPC.Document, parentObject: Any?, priority: Int, cacheType: Int): Boolean {
        if (!isSticker(sticker) || NaConfig.externalStickerCacheUri == null) return false
        val cached = cachedDocuments[sticker.id] ?: return false
        if (!cached.isFile || cached.length() <= 0) {
            cachedDocuments.remove(sticker.id)
            return false
        }

        val fileLoader = FileLoader.getInstance(account)
        val destination = getInternalCacheFile(sticker)
        if (destination.isFile && destination.length() > 0) return false
        val restoreKey = "$account:${sticker.id}"
        if (!restoringDocuments.add(restoreKey)) return true

        scope.launch {
            val restored = syncMutex.withLock { copyToInternal(cached, destination) }
            restoringDocuments.remove(restoreKey)
            if (restored) {
                fileLoader.notifyFileLoadedFromLocalStickerCache(sticker, destination, parentObject)
            } else {
                cachedDocuments.remove(sticker.id)
                fileLoader.loadFile(sticker, parentObject, priority, cacheType)
            }
        }
        return true
    }

    @JvmStatic
    fun onTelegramFileLoaded(account: Int, document: TLRPC.Document?, finalFile: File?) {
        if (document == null || finalFile == null || !finalFile.isFile || finalFile.length() <= 0) return
        if (!isSticker(document) || NaConfig.externalStickerCacheUri == null) return
        scope.launch {
            try {
                syncMutex.withLock {
                    val root = getManagedRoot(create = true) ?: return@withLock
                    val setDirectory = root.findFile(getStickerDirectoryName(account, document))
                        ?: root.createDirectory(getStickerDirectoryName(account, document))
                        ?: return@withLock
                    if (setDirectory.isDirectory) {
                        copySticker(finalFile, document, setDirectory)
                    }
                }
                scheduleStatsRefresh()
            } catch (e: Exception) {
                FileLog.e(e)
            }
        }
    }

    @JvmStatic
    fun addStatsListener(listener: StatsListener) {
        statsListeners.addIfAbsent(listener)
        loadStickerSets(UserConfig.selectedAccount, ALL_TYPES) {
            scheduleStatsRefresh()
        }
    }

    @JvmStatic
    fun removeStatsListener(listener: StatsListener) {
        statsListeners.remove(listener)
    }

    private fun scheduleAutoSync(account: Int) {
        if (NaConfig.externalStickerCacheUri == null) return
        synchronized(this) {
            autoSyncJob?.cancel()
            autoSyncJob = scope.launch {
                delay(AUTO_SYNC_DELAY_MS)
                try {
                    cacheInstalledStickers(account, ALL_TYPES, prefetchMissing = false, publishProgress = false)
                    scheduleStatsRefresh()
                } catch (e: Exception) {
                    FileLog.e(e)
                }
            }
        }
    }

    private data class SyncResult(val copied: Int, val queued: Int)

    private suspend fun cacheInstalledStickers(account: Int, types: IntArray, prefetchMissing: Boolean, publishProgress: Boolean): SyncResult {
        var copied = 0
        var queued = 0
        syncMutex.withLock {
            val root = getManagedRoot(create = true) ?: return SyncResult(0, 0)
            val fileLoader = FileLoader.getInstance(account)
            for (set in getInstalledStickerSets(account, types)) {
                val setDirectory = root.findFile(getStickerDirectoryName(set))
                    ?: root.createDirectory(getStickerDirectoryName(set))
                    ?: continue
                if (!setDirectory.isDirectory) continue

                for (sticker in set.documents) {
                    val source = findLocalFile(sticker)
                    if (source != null) {
                        if (copySticker(source, sticker, setDirectory)) {
                            copied++
                            if (publishProgress) {
                                publishStatsSnapshot(account)
                            }
                        }
                    } else if (prefetchMissing && cachedDocuments[sticker.id] == null) {
                        fileLoader.loadFile(sticker, set, FileLoader.PRIORITY_LOW, 1)
                        queued++
                        delay(PREFETCH_DELAY_MS)
                    }
                }
            }
        }
        return SyncResult(copied, queued)
    }

    private fun getInstalledStickerSets(account: Int, types: IntArray = ALL_TYPES): List<TL_messages_stickerSet> {
        val controller = MediaDataController.getInstance(account)
        return types.flatMap { controller.getStickerSets(it) }
    }

    private fun findLocalFile(sticker: TLRPC.Document): File? {
        val fileName = FileLoader.getAttachFileName(sticker)
        return listOf(
            getInternalCacheFile(sticker),
            File(FileLoader.getDirectory(FileLoader.MEDIA_DIR_DOCUMENT), fileName),
            File(FileLoader.getDirectory(FileLoader.MEDIA_DIR_VIDEO), fileName),
            File(FileLoader.getDirectory(FileLoader.MEDIA_DIR_FILES), fileName),
        ).distinctBy { it.absolutePath }.firstOrNull { it.isFile && it.length() > 0 }
    }

    private fun getInternalCacheFile(sticker: TLRPC.Document): File {
        return File(FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE), FileLoader.getAttachFileName(sticker))
    }

    private fun copyToInternal(source: DocumentFile, destination: File): Boolean {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, "${destination.name}.nagram_tmp")
        return try {
            ApplicationLoader.applicationContext.contentResolver.openInputStream(source.uri)?.use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            } ?: return false
            if (!temporary.renameTo(destination)) {
                temporary.inputStream().use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                }
                temporary.delete()
            }
            destination.isFile && destination.length() > 0
        } catch (e: Exception) {
            temporary.delete()
            FileLog.e(e)
            false
        }
    }

    private fun copySticker(source: File, sticker: TLRPC.Document, setDirectory: DocumentFile): Boolean {
        val extension = source.extension.ifBlank { extensionFor(sticker.mime_type) }
        val destinationName = "${sticker.id}_high.$extension"
        val existing = setDirectory.findFile(destinationName)
        if (existing?.let { it.isFile && it.length() > 0 } == true) {
            cachedDocuments[sticker.id] = existing
            return false
        }
        if (existing != null) {
            existing.delete()
        }

        val destination = setDirectory.createFile(mimeTypeFor(sticker.mime_type), destinationName) ?: return false
        try {
            ApplicationLoader.applicationContext.contentResolver.openOutputStream(destination.uri, "wt")?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: destination.delete()
            if (destination.isFile && destination.length() > 0) {
                cachedDocuments[sticker.id] = destination
                return true
            }
            return false
        } catch (e: Exception) {
            destination.delete()
            throw e
        }
    }

    private fun rebuildIndex() {
        cachedDocuments.clear()
        getManagedRoot(create = false)?.listFiles()?.forEach { setDirectory ->
            if (!setDirectory.isDirectory) return@forEach
            setDirectory.listFiles().forEach { file ->
                val id = file.name?.substringBefore('_')?.toLongOrNull()
                if (id != null && file.isFile && file.length() > 0) cachedDocuments[id] = file
            }
        }
    }

    private fun calculateStats(): CacheStats {
        rebuildIndex()
        val totalBytes = cachedDocuments.values.sumOf { it.length().coerceAtLeast(0) }
        val totalCount = getInstalledStickerSets(UserConfig.selectedAccount)
            .asSequence()
            .flatMap { it.documents.asSequence() }
            .map { it.id }
            .distinct()
            .count()
        return CacheStats(totalBytes, cachedDocuments.size, maxOf(totalCount, cachedDocuments.size))
    }

    private fun publishStatsSnapshot(account: Int) {
        val totalBytes = cachedDocuments.values.sumOf { it.length().coerceAtLeast(0) }
        val totalCount = getInstalledStickerSets(account)
            .asSequence()
            .flatMap { it.documents.asSequence() }
            .map { it.id }
            .distinct()
            .count()
        val stats = CacheStats(totalBytes, cachedDocuments.size, maxOf(totalCount, cachedDocuments.size))
        AndroidUtilities.runOnUIThread {
            statsListeners.forEach { it.onCacheStatsChanged(stats) }
        }
    }

    private fun scheduleStatsRefresh() {
        synchronized(this) {
            statsJob?.cancel()
            statsJob = scope.launch {
                delay(100)
                val stats = calculateStats()
                AndroidUtilities.runOnUIThread {
                    statsListeners.forEach { it.onCacheStatsChanged(stats) }
                }
            }
        }
    }

    private fun getManagedRoot(create: Boolean): DocumentFile? {
        val uri = NaConfig.externalStickerCacheUri ?: return null
        val parent = DocumentFile.fromTreeUri(ApplicationLoader.applicationContext, uri) ?: return null
        if (!parent.isDirectory || !parent.canWrite()) return null
        val existing = parent.findFile(MANAGED_DIRECTORY)
        return when {
            existing?.isDirectory == true -> existing
            existing != null -> null
            create -> parent.createDirectory(MANAGED_DIRECTORY)
            else -> null
        }
    }

    private fun getStickerDirectoryName(set: TL_messages_stickerSet): String {
        val name = set.set.short_name.ifBlank { set.set.id.toString() }
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    private fun getStickerDirectoryName(account: Int, sticker: TLRPC.Document): String {
        getInstalledStickerSets(account).firstOrNull { set -> set.documents.any { it.id == sticker.id } }?.let {
            return getStickerDirectoryName(it)
        }
        val setId = MediaDataController.getStickerSetId(sticker)
        return (if (setId != -1L) setId.toString() else "uncategorized")
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    private fun loadStickerSets(account: Int, types: IntArray = ALL_TYPES, onDone: () -> Unit) {
        AndroidUtilities.runOnUIThread {
            val controller = MediaDataController.getInstance(account)
            var remaining = types.size
            fun done() {
                remaining--
                if (remaining <= 0) {
                    onDone()
                }
            }
            types.forEach { type ->
                controller.loadStickers(type, false, true, true) { done() }
            }
        }
    }

    private fun isSticker(document: TLRPC.Document): Boolean {
        return MessageObject.isStickerDocument(document)
                || MessageObject.isAnimatedStickerDocument(document, true)
                || MessageObject.isVideoStickerDocument(document)
                || MessageObject.isAnimatedEmoji(document)
    }

    private fun extensionFor(mimeType: String?): String = when (mimeType) {
        "application/x-tgsticker" -> "tgs"
        "video/webm" -> "webm"
        else -> "webp"
    }

    private fun mimeTypeFor(mimeType: String?): String = mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"

    private val observer = NotificationCenterDelegate { _, account, _ -> scheduleAutoSync(account) }
    private val notificationIds = intArrayOf(
        NotificationCenter.fileLoaded,
        NotificationCenter.stickersDidLoad,
        NotificationCenter.diceStickersDidLoad,
        NotificationCenter.featuredStickersDidLoad,
        NotificationCenter.stickersImportComplete,
    )

    @JvmStatic
    fun addNotificationObservers(account: Int) {
        if (!observedAccounts.add(account)) return
        NotificationCenter.getInstance(account).apply {
            notificationIds.forEach { addObserver(observer, it) }
        }
        if (NaConfig.externalStickerCacheUri != null) {
            loadStickerSets(account, ALL_TYPES) {
                scope.launch {
                    try {
                        rebuildIndex()
                        cacheInstalledStickers(account, ALL_TYPES, prefetchMissing = false, publishProgress = false)
                        scheduleStatsRefresh()
                    } catch (e: Exception) {
                        FileLog.e(e)
                    }
                }
            }
        }
    }

    @JvmStatic
    fun removeNotificationObservers(account: Int) {
        if (!observedAccounts.remove(account)) return
        NotificationCenter.getInstance(account).apply {
            notificationIds.forEach { removeObserver(observer, it) }
        }
    }

    private fun showToast(message: String) {
        AndroidUtilities.runOnUIThread { AlertUtil.showToast(message) }
    }
}
