package chat.keryx.app.presentation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit

/** The gateway's petdex mascot: the drawer sprite and the adopt-a-pet picker. */
class PetDelegate(deps: GatewayDeps) {
    private val scope = deps.scope
    private val settings = deps.settings
    private val client = deps.client
    private val bareClient = deps.bareClient
    private val toast = deps.toast

    private companion object {
        // Decoded pet-picker thumbnails were unbounded before 1.19.0.
        const val PET_THUMB_MAX = 120
    }

    /** The gateway's active petdex mascot for the drawer header (null until fetched, or when the
     *  gateway has no pet configured). Spritesheet payload is ~2MB, so it's fetched once per app
     *  session — the drawer triggers this on open. */
    private val _petInfo = MutableStateFlow<chat.keryx.app.data.remote.HermesStreamClient.PetInfo?>(null)
    val petInfo: StateFlow<chat.keryx.app.data.remote.HermesStreamClient.PetInfo?> = _petInfo.asStateFlow()
    private var petFetchDone = false

    fun refreshPet() {
        if (petFetchDone) return
        val client = bareClient() ?: return
        petFetchDone = true
        scope.launch {
            client.pet()
                .onSuccess {
                    if (it.enabled && it.spritesheetBase64.isNotEmpty()) {
                        // Decode the sheet once, process-wide, and DROP the ~2 MB base64 string
                        // instead of pinning it in this StateFlow for the whole session.
                        chat.keryx.app.presentation.ui.components.PetSheetMemo.prime(it)
                        _petInfo.value = it.copy(spritesheetBase64 = "")
                    }
                }
                // Transient failure (gateway restarting, phone off WiFi) → retry on next drawer open.
                .onFailure { petFetchDone = false }
        }
    }

    // --- Pet picker (tap the drawer mascot) -----------------------------------------------------

    /** Adoptable pets: installed merged with the petdex catalog (~3.4k entries). */
    private val _petGallery = MutableStateFlow<chat.keryx.app.data.remote.HermesStreamClient.PetGallery?>(null)
    val petGallery: StateFlow<chat.keryx.app.data.remote.HermesStreamClient.PetGallery?> = _petGallery.asStateFlow()
    private val _petGalleryLoading = MutableStateFlow(false)
    val petGalleryLoading: StateFlow<Boolean> = _petGalleryLoading.asStateFlow()

    /** Slug currently being adopted (spinner on that row), or null. */
    private val _petSelecting = MutableStateFlow<String?>(null)
    val petSelecting: StateFlow<String?> = _petSelecting.asStateFlow()
    private val _petSelectError = MutableStateFlow<String?>(null)
    val petSelectError: StateFlow<String?> = _petSelectError.asStateFlow()

    /** Row-preview thumbs by slug. LRU-bounded (the catalog is ~3.4k pets — a fast scroll used to
     *  retain a Bitmap for every row ever composed, with no eviction); an evicted slug leaves
     *  [petThumbRequested] too so re-scrolling it refetches. The published StateFlow map is an
     *  immutable snapshot rebuilt on change (≤ [PET_THUMB_MAX] entries — cheap). */
    private val _petThumbs = MutableStateFlow<Map<String, android.graphics.Bitmap>>(emptyMap())
    val petThumbs: StateFlow<Map<String, android.graphics.Bitmap>> = _petThumbs.asStateFlow()
    private val petThumbRequested = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val petThumbLru = object : LinkedHashMap<String, android.graphics.Bitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, android.graphics.Bitmap>): Boolean {
            val evict = size > PET_THUMB_MAX
            if (evict) petThumbRequested.remove(eldest.key)
            return evict
        }
    }
    // Uninstalled previews make the gateway download that pet's sheet from the petdex CDN —
    // a fast scroll through the catalog must not turn into dozens of parallel downloads.
    private val petThumbGate = kotlinx.coroutines.sync.Semaphore(3)

    fun refreshPetGallery() {
        val client = client() ?: return
        if (_petGalleryLoading.value) return
        _petGalleryLoading.value = true
        scope.launch {
            // Two-phase like the desktop picker: installed pets render instantly,
            // the full catalog (a remote manifest fetch) follows.
            if (_petGallery.value == null) {
                client.petGallery(localOnly = true).onSuccess { _petGallery.value = it }
            }
            client.petGallery(localOnly = false).onSuccess { full ->
                if (full.pets.isNotEmpty()) _petGallery.value = full
            }
            _petGalleryLoading.value = false
        }
    }

    fun requestPetThumb(slug: String, url: String) {
        if (_petThumbs.value.containsKey(slug) || !petThumbRequested.add(slug)) return
        val client = client() ?: run { petThumbRequested.remove(slug); return }
        scope.launch {
            petThumbGate.withPermit {
                client.petThumb(slug, url)
                    .onSuccess { bytes ->
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let {
                            synchronized(petThumbLru) {
                                petThumbLru[slug] = it
                                _petThumbs.value = petThumbLru.toMap()
                            }
                        }
                    }
                    // Transient (offline, CDN hiccup) — allow a retry when the row shows again.
                    .onFailure { petThumbRequested.remove(slug) }
            }
        }
    }

    fun selectPet(slug: String) {
        val client = client() ?: return
        if (_petSelecting.value != null) return
        _petSelecting.value = slug
        _petSelectError.value = null
        scope.launch {
            client.petSelect(slug)
                .onSuccess {
                    // New active pet — refetch the drawer sprite and mirror the state locally.
                    petFetchDone = false
                    refreshPet()
                    _petGallery.value = _petGallery.value?.let { g ->
                        g.copy(
                            enabled = true,
                            active = slug,
                            pets = g.pets.map { p -> if (p.slug == slug) p.copy(installed = true) else p },
                        )
                    }
                }
                .onFailure { _petSelectError.value = it.message ?: "could not adopt $slug" }
            _petSelecting.value = null
        }
    }


    /** Memory-pressure hook (CacheRegistry, via the ViewModel): decoded thumbs re-fetch on demand. */
    fun trimThumbs() {
        synchronized(petThumbLru) {
            petThumbLru.clear()
            petThumbRequested.clear()
            _petThumbs.value = emptyMap()
        }
    }
}
