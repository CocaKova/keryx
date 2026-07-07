package chat.keryx.app.presentation.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.keryx.app.data.remote.HermesStreamClient.PetGalleryEntry
import chat.keryx.app.presentation.ChatViewModel

/**
 * Pet adoption picker — opened by tapping the drawer mascot. Lists installed pets first (instant),
 * then the full petdex catalog (~3.4k pets, curated first) with search. Tapping a row adopts it:
 * the gateway installs the sheet if needed and flips `display.pet.*`, so the desktop and TUI
 * follow — one pet everywhere, same as changing it from the desktop.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PetPickerSheet(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
) {
    val gallery by viewModel.petGallery.collectAsState()
    val loading by viewModel.petGalleryLoading.collectAsState()
    val selecting by viewModel.petSelecting.collectAsState()
    val selectError by viewModel.petSelectError.collectAsState()
    val thumbs by viewModel.petThumbs.collectAsState()
    val petInfo by viewModel.petInfo.collectAsState()

    var query by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 16.dp)
                .padding(bottom = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Choose a pet", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = gallery?.pets?.firstOrNull { it.slug == gallery?.active }?.displayName
                            ?.let { "$it rides along in the drawer" } ?: "Pets live on the gateway — every surface shows the same one",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // The reigning pet, live-animated (idle loop) while you shop for its successor.
                petInfo?.let { pet ->
                    PetSprite(
                        info = pet,
                        pose = PetPose.IDLE,
                        running = true,
                        modifier = Modifier.size(width = 44.dp, height = 48.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search ${gallery?.pets?.size ?: 0} pets…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            if (loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            selectError?.let {
                Text(
                    text = it,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            val pets = gallery?.pets.orEmpty()
            // Installed first; catalog curated-then-alphabetical. Search spans both.
            val (installedPets, catalogPets) = remember(pets, query) {
                val q = query.trim()
                val visible = if (q.isBlank()) pets
                    else pets.filter { it.displayName.contains(q, ignoreCase = true) || it.slug.contains(q, ignoreCase = true) }
                Pair(
                    visible.filter { it.installed }.sortedBy { it.displayName.lowercase() },
                    visible.filter { !it.installed }
                        .sortedWith(compareByDescending<PetGalleryEntry> { it.curated }.thenBy { it.displayName.lowercase() }),
                )
            }

            LazyColumn(modifier = Modifier.weight(1f).padding(top = 8.dp)) {
                if (installedPets.isNotEmpty()) {
                    item(key = "hdr-installed") { PetSectionHeader("Installed") }
                    items(installedPets, key = { "i-${it.slug}" }) { pet ->
                        PetRow(pet, gallery?.active == pet.slug, selecting, thumbs[pet.slug]?.asImageBitmap(), viewModel)
                    }
                }
                if (catalogPets.isNotEmpty()) {
                    item(key = "hdr-catalog") { PetSectionHeader("Petdex catalog") }
                    items(catalogPets, key = { "c-${it.slug}" }) { pet ->
                        PetRow(pet, active = false, selecting, thumbs[pet.slug]?.asImageBitmap(), viewModel)
                    }
                }
                if (!loading && installedPets.isEmpty() && catalogPets.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            text = if (query.isBlank()) "No pets available — is Hermes Link configured?" else "No pets match \"$query\"",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PetSectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun PetRow(
    pet: PetGalleryEntry,
    active: Boolean,
    selecting: String?,
    thumb: androidx.compose.ui.graphics.ImageBitmap?,
    viewModel: ChatViewModel,
) {
    // Lazy per-visible-row preview; the VM throttles concurrent fetches so a fast
    // scroll doesn't turn into a CDN download storm on the gateway.
    LaunchedEffect(pet.slug) { viewModel.requestPetThumb(pet.slug, pet.spritesheetUrl) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                else androidx.compose.ui.graphics.Color.Transparent
            )
            .clickable(enabled = selecting == null && !active) { viewModel.selectPet(pet.slug) }
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (thumb != null) {
                Image(
                    bitmap = thumb,
                    contentDescription = pet.displayName,
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.None,
                    modifier = Modifier.size(36.dp),
                )
            } else {
                Text("·ᴥ·", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = pet.displayName,
                fontSize = 14.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val badge = when {
                active -> "active"
                pet.generated -> "hatched locally"
                pet.curated -> "curated"
                pet.installed -> "installed"
                else -> ""
            }
            if (badge.isNotEmpty()) {
                Text(badge, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        when {
            selecting == pet.slug -> CircularProgressIndicator(
                modifier = Modifier.size(18.dp), strokeWidth = 2.dp,
            )
            active -> Icon(
                Icons.Default.Check,
                contentDescription = "Active",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
