package com.najah.mealannouncer.feature.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.najah.mealannouncer.core.datastore.AiSecretStore
import com.najah.mealannouncer.core.datastore.ShoppingSettings
import com.najah.mealannouncer.core.datastore.SupermarketChain
import com.najah.mealannouncer.core.model.FoodProduct
import com.najah.mealannouncer.data.mistral.MistralException
import com.najah.mealannouncer.data.mistral.ReceiptItem
import com.najah.mealannouncer.data.mistral.ReceiptReader
import com.najah.mealannouncer.data.mistral.ReceiptReading
import com.najah.mealannouncer.data.openfoodfacts.FoodRepository
import com.najah.mealannouncer.data.openfoodfacts.OffDatabase
import com.najah.mealannouncer.data.openfoodfacts.ReceiptMatch
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import javax.inject.Inject

data class ShoppingUiState(
    val selectedChains: Set<SupermarketChain> = emptySet(),
    val pantryCount: Int = 0,
    val pantryPreview: List<String> = emptyList(),
    val lastReceiptSummary: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

data class ShoppingActions(
    val onToggleChain: (SupermarketChain) -> Unit,
    val onImportFromDevice: () -> Unit,
    val onImportFromGoogleDrive: () -> Unit,
    val onClearPantry: () -> Unit,
)

@HiltViewModel
class ShoppingSettingsViewModel @Inject constructor(
    private val shopping: ShoppingSettings,
    private val offDatabase: OffDatabase,
    private val foodRepository: FoodRepository,
    private val receiptReader: ReceiptReader,
    private val aiKeys: AiSecretStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(fromStore(message = null, busy = false))
    val state: StateFlow<ShoppingUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            shopping.config.collect {
                _state.update { current ->
                    fromStore(message = current.message, busy = current.busy)
                }
            }
        }
    }

    private fun fromStore(message: String?, busy: Boolean): ShoppingUiState {
        val cfg = shopping.config.value
        return ShoppingUiState(
            selectedChains = cfg.chains,
            pantryCount = cfg.pantryProducts.size,
            pantryPreview = cfg.pantryProducts.take(6).map { it.displayName() },
            lastReceiptSummary = cfg.lastReceiptSummary,
            busy = busy,
            message = message,
        )
    }

    fun toggleChain(chain: SupermarketChain) {
        shopping.toggleChain(chain)
    }

    fun clearPantry() {
        shopping.clearPantry()
        _state.update { fromStore(message = "Garde-manger personnel vidé.", busy = false) }
    }

    /**
     * Lit une facture ou un ticket de caisse choisi sur le téléphone ou dans Google Drive
     * (PDF, photo JPEG/PNG, ou fichier texte), retrouve les produits correspondants dans la base
     * locale Open Food Facts (`off-base.db`) et les enregistre dans le garde-manger personnel.
     */
    fun importReceipt(uri: Uri) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = "Lecture de la facture ou du ticket en cours…") }
        viewModelScope.launch {
            val message = withContext(Dispatchers.IO) {
                processReceiptUri(uri)
            }
            _state.update { fromStore(message = message, busy = false) }
        }
    }

    private suspend fun processReceiptUri(uri: Uri): String {
        val key = aiKeys.read()
        if (key.isNullOrBlank()) {
            return "Clé IA OpenRouter manquante : renseignez-la en haut des Réglages pour analyser une facture."
        }
        val mime = context.contentResolver.getType(uri)?.lowercase().orEmpty()
        val reading = try {
            when {
                mime.contains("pdf") || uri.toString().lowercase().endsWith(".pdf") -> {
                    val pages = renderPdfPagesToJpeg(uri)
                    if (pages.isEmpty()) return "Impossible de lire ce fichier PDF."
                    mergeReadings(pages.map { pageJpeg -> receiptReader.readImage(key, pageJpeg) })
                }
                mime.startsWith("text/") || mime.contains("csv") -> {
                    val text = context.contentResolver.openInputStream(uri)?.use {
                        it.bufferedReader().readText()
                    }.orEmpty()
                    if (text.isBlank()) return "Ce fichier texte est vide."
                    receiptReader.readText(key, text)
                }
                else -> {
                    val jpeg = decodeImageToJpeg(uri) ?: return "Impossible de lire cette image ou ce document."
                    receiptReader.readImage(key, jpeg)
                }
            }
        } catch (error: MistralException) {
            return "Erreur lors de la lecture de la facture : ${error.message}"
        } catch (error: Exception) {
            return "Facture illisible : ${error.message ?: "format non reconnu"}"
        }

        if (reading.items.isEmpty()) {
            return "Aucun article alimentaire n'a été détecté sur ce document."
        }

        val detectedChain = SupermarketChain.detectFromStoreName(reading.storeChain)
        if (detectedChain != null) {
            shopping.addChain(detectedChain)
        }
        val preferredBrands = buildList {
            if (detectedChain != null) addAll(detectedChain.brandKeywords)
            addAll(shopping.config.value.preferredBrandKeywords)
        }.distinct()

        val matchedProducts = mutableListOf<FoodProduct>()
        val now = Instant.now()
        // Fruits, légumes, pain de boulangerie vendus au poids : ce ne sont pas des produits
        // industriels ; la banque de portions en images s'en charge au moment du repas.
        val isFresh: (ReceiptItem) -> Boolean = { (it.fresh || ReceiptMatch.isFreshProduce(it.name, it.brand)) && it.barcode == null }
        val fresh = reading.items.count(isFresh)
        for (item in reading.items) {
            if (isFresh(item)) continue
            val found = matchReceiptItemInOff(item, preferredBrands) ?: continue
            val stamped = found.copy(verifiedByUser = true, updatedAt = now)
            matchedProducts += stamped
            runCatching { foodRepository.saveVerified(stamped) }
        }

        if (matchedProducts.isEmpty()) {
            return "Articles lus (${reading.items.size}), mais aucun n'a pu être rattaché à la base Open Food Facts."
        }

        val chainLabel = detectedChain?.label ?: reading.storeChain ?: "courses"
        val examples = matchedProducts.take(3).joinToString(", ") { it.displayName() }
        val freshNote = if (fresh > 0) " $fresh produit(s) frais au poids laissé(s) à la banque de portions." else ""
        val summary = "Dernière facture ($chainLabel) : ${matchedProducts.size} produit(s) reconnu(s) sur ${reading.items.size} ($examples…).$freshNote"
        shopping.recordPurchasedProducts(matchedProducts, summary)
        return summary
    }

    private suspend fun matchReceiptItemInOff(
        item: ReceiptItem,
        preferredBrands: List<String>,
    ): FoodProduct? {
        item.barcode?.let { code ->
            offDatabase.findByBarcode(code)?.takeIf { it.hasCarbData() }?.let { return it }
        }
        val queryWithBrand = listOfNotNull(item.name, item.brand).joinToString(" ").trim()
        val itemBrands = buildList {
            item.brand?.let { add(it) }
            addAll(preferredBrands)
        }.distinct()
        val candidates = offDatabase.search(queryWithBrand, limit = 10, preferredBrands = itemBrands)
            .ifEmpty { offDatabase.search(item.name, limit = 10, preferredBrands = itemBrands) }
        // Garde-fou du 10/11/2026 : « bananes » ne devient plus « bananes séchées ».
        return candidates.firstOrNull { candidate ->
            ReceiptMatch.accept(item.name, candidate.name, candidate.carbsPer100g ?: candidate.carbsPer100ml)
        }
    }

    private fun mergeReadings(readings: List<ReceiptReading>): ReceiptReading {
        val store = readings.firstNotNullOfOrNull { it.storeChain }
        val items = readings.flatMap { it.items }.distinctBy { "${it.name.lowercase()}|${it.brand?.lowercase().orEmpty()}" }
        return ReceiptReading(storeChain = store, items = items)
    }

    /** Convertit les premières pages d'un PDF de facture Drive en JPEG lisibles par la vision. */
    private fun renderPdfPagesToJpeg(uri: Uri): List<ByteArray> {
        val tempFile = File.createTempFile("receipt-", ".pdf", context.cacheDir)
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return emptyList()
            ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    val pageCount = renderer.pageCount.coerceAtMost(MAX_PDF_PAGES)
                    buildList {
                        for (index in 0 until pageCount) {
                            renderer.openPage(index).use { page ->
                                val scale = (1600f / maxOf(page.width, page.height, 1)).coerceIn(1f, 2.5f)
                                val width = (page.width * scale).toInt().coerceAtLeast(1)
                                val height = (page.height * scale).toInt().coerceAtLeast(1)
                                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                Canvas(bitmap).drawColor(AndroidColor.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                val out = ByteArrayOutputStream()
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                                bitmap.recycle()
                                add(out.toByteArray())
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        } finally {
            tempFile.delete()
        }
    }

    private fun decodeImageToJpeg(uri: Uri): ByteArray? = try {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val maxSide = maxOf(bitmap.width, bitmap.height)
        val scaled = if (maxSide > 1600) {
            val ratio = 1600f / maxSide
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt().coerceAtLeast(1),
                (bitmap.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        out.toByteArray()
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val MAX_PDF_PAGES = 3
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShoppingSettingsSection(state: ShoppingUiState, actions: ShoppingActions) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.settings_shopping_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.settings_shopping_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.settings_shopping_chains_label),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SupermarketChain.entries.forEach { chain ->
                    val selected = chain in state.selectedChains
                    FilterChip(
                        selected = selected,
                        onClick = { actions.onToggleChain(chain) },
                        label = { Text(chain.label) },
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.settings_shopping_receipt_title),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_shopping_receipt_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = actions.onImportFromDevice,
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.settings_shopping_import_device))
                }
                OutlinedButton(
                    onClick = actions.onImportFromGoogleDrive,
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.settings_shopping_import_drive))
                }
            }
            if (state.pantryCount > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        R.string.settings_shopping_pantry_count,
                        state.pantryCount,
                        state.pantryPreview.joinToString(", "),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            state.lastReceiptSummary?.let { summary ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.message?.let { msg ->
                if (msg != state.lastReceiptSummary) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (state.pantryCount > 0) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = actions.onClearPantry, enabled = !state.busy) {
                    Text(stringResource(R.string.settings_shopping_clear_pantry))
                }
            }
        }
    }
}
