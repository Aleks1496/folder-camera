package org.foldercamera.ui

import android.app.Application
import androidx.compose.ui.geometry.Rect
import java.io.File
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.foldercamera.FolderCameraApp
import org.foldercamera.core.*
import org.foldercamera.data.*

data class CaptureDraft(val file: File, val root: String, val path: String, val receiverId: String?)

class CameraModel(application: Application) : AndroidViewModel(application) {
    val app = application as FolderCameraApp
    val gate = DestinationGate()
    var screen by mutableStateOf("destination")
    var path by mutableStateOf(app.config.lastPath)
    var root by mutableStateOf(app.config.root)
    var sync by mutableStateOf(app.config.syncEnabled)
    var receiver by mutableStateOf(runCatching { app.config.receiver() }.getOrNull())
    var receiverToReplace by mutableStateOf<Receiver?>(null)
    var error by mutableStateOf<String?>(null)
    var lightMode by mutableStateOf(LightMode.OFF)
    var zoomRatio by mutableFloatStateOf(1f)
    var folderPaths by mutableStateOf<List<String>>(emptyList())
    var folderLoading by mutableStateOf(false)
    var folderError by mutableStateOf(false)
    var rootName by mutableStateOf<String?>(null)
    private var folderJob: Job? = null
    private var folderGeneration = 0
    private var cleared = false
    fun refreshFolders() {
        val selected = root ?: return
        folderJob?.cancel(); val generation = ++folderGeneration; folderLoading = true; folderError = false
        folderJob = viewModelScope.launch {
            try {
                val (name, paths) = org.foldercamera.storage.FolderCatalog(app).scan(selected) { paths ->
                    withContext(Dispatchers.Main) { if (root == selected && generation == folderGeneration) folderPaths = paths }
                }
                if (root == selected && generation == folderGeneration) { rootName = name; folderPaths = paths }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { if (root == selected && generation == folderGeneration) folderError = true }
            finally { if (root == selected && generation == folderGeneration) folderLoading = false }
        }
    }
    var busy by mutableStateOf(false)
        private set
    var draft by mutableStateOf<CaptureDraft?>(null)
        private set
    var cropSaveFailed by mutableStateOf(false)
        private set
    val captures = app.db.dao().observeCaptures().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val deliveries = app.db.dao().observeDeliveries().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun confirm() {
        error = PortablePath.validate(path)
        if (error == null && root != null) { gate.confirm(root!!, path); app.config.lastPath = path; screen = "camera" }
    }
    fun back() {
        if (draft != null) {
            discardDraft()
            return
        }
        if (busy) return
        gate.session?.let { path = it.path }
        screen = if (gate.session != null) "camera" else "destination"
    }
    fun selectRoot(uri: String) {
        root = uri; folderPaths = emptyList(); rootName = null; app.config.root = uri; gate.reset(); screen = "destination"; error = null; refreshFolders()
    }
    fun retryLocal() { app.scope.launch { app.store.reconcile(); app.delivery.kick() } }
    fun toggleSync(value: Boolean) { sync = value; app.config.syncEnabled = value; if (value) app.delivery.kick() else app.delivery.pause() }
    fun retryNetwork() { app.scope.launch { receiver?.let { app.db.dao().retry(it.id) }; app.delivery.kick() } }
    // Complete this short local change even if the calling screen leaves composition.
    suspend fun connectReceiver(next: Receiver, replace: Boolean = false) = withContext(NonCancellable) {
        val differentPc = receiver?.let { it.id != next.id } == true
        if (differentPc && !replace) {
            receiverToReplace = next; screen = "settings"
            return@withContext
        }
        val resume = sync
        if (differentPc) toggleSync(false)
        try {
            withContext(Dispatchers.IO) {
                check(org.foldercamera.sync.ReceiverConnections(app.db.dao(), app.config::receiver, app.config::setReceiver).connect(next, replace))
            }
            receiver = next; receiverToReplace = null; error = null
            screen = "settings"
        } finally {
            if (differentPc) toggleSync(resume)
        }
        app.delivery.kick()
    }
    fun importFolders(paths: Set<String>) {
        val selectedReceiver = receiver ?: return
        app.scope.launch {
            app.db.dao().saved().filter { it.relativePath in paths }.forEach { app.db.dao().enqueue(Delivery(it.photoId, selectedReceiver.id)) }
            app.delivery.kick()
        }
    }
    fun capture(take: (File, (Boolean) -> Unit) -> Unit) {
        val session = gate.session ?: return
        if (busy || draft != null) return
        busy = true
        val boundReceiver = if (sync) receiver?.id else null
        viewModelScope.launch {
            var file: File? = null
            try {
                val output = app.store.newDraft()
                file = output
                take(output) { success ->
                    busy = false
                    if (success && !cleared && screen == "camera") {
                        draft = CaptureDraft(output, session.root, session.path, boundReceiver)
                        cropSaveFailed = false
                    } else {
                        app.scope.launch { output.delete() }
                        if (!success) error = "capture_failed"
                    }
                }
            } catch (cancelled: CancellationException) {
                file?.let { app.scope.launch { it.delete() } }
                throw cancelled
            } catch (_: Exception) {
                file?.let { app.scope.launch { it.delete() } }
                busy = false
                error = "storage_unavailable"
            }
        }
    }

    fun discardDraft() {
        val current = draft ?: return
        if (busy) return
        draft = null
        cropSaveFailed = false
        app.scope.launch { current.file.delete() }
    }

    fun saveDraft(crop: Rect) {
        val current = draft ?: return
        if (busy) return
        busy = true
        cropSaveFailed = false
        viewModelScope.launch {
            try {
                withContext(NonCancellable) {
                    val capture = withContext(Dispatchers.IO) {
                        val edited = if (crop == FullPhoto) null else app.store.newDraft()
                        try {
                            if (edited != null) writeCroppedPhoto(current.file, edited, crop)
                            app.store.begin(current.root, current.path, current.receiverId, edited ?: current.file)
                        } finally {
                            edited?.delete()
                        }
                    }
                    app.scope.launch {
                        current.file.delete()
                        app.store.persist(capture.photoId)
                        app.delivery.kick()
                    }
                    draft = null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                cropSaveFailed = true
            } finally {
                busy = false
            }
        }
    }

    override fun onCleared() {
        cleared = true
        if (!busy) draft?.let { current -> app.scope.launch { current.file.delete() } }
        super.onCleared()
    }
}
