package com.raen.crunchlab.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.math.min

data class CbzMangaItem(
    val file: File,
    val title: String,
    val parentFolder: String,
    val sizeMb: Float
)

data class CbzPageEntry(
    val index: Int,
    val entryName: String,
    val displayName: String,
    var thumbnail: Bitmap? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CbzBrowserSheet(
    onDismiss: () -> Unit,
    onLoadPages: (List<Pair<String, Bitmap>>) -> Unit,
    onTranslateCbz: (
        pages: List<Pair<String, Bitmap>>,
        sourceFile: File,
        metadata: com.raen.crunchlab.data.CrunchExporter.CbzArchiveMetadata,
        pageIndices: List<Int>
    ) -> Unit = { _, _, _, _ -> }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var availableArchives by remember { mutableStateOf<List<CbzMangaItem>>(emptyList()) }
    var selectedArchive by remember { mutableStateOf<CbzMangaItem?>(null) }
    var customArchiveFile by remember { mutableStateOf<File?>(null) }

    var autoStartTranslate by remember { mutableStateOf(true) }
    var isScanningArchives by remember { mutableStateOf(true) }
    var isExtractingPages by remember { mutableStateOf(false) }
    var extractionProgressText by remember { mutableStateOf("") }
    var extractionProgress by remember { mutableFloatStateOf(0f) }

    var pageEntries by remember { mutableStateOf<List<CbzPageEntry>>(emptyList()) }
    val selectedPageIndices = remember { mutableStateListOf<Int>() }

    // Natural alphanumeric sorting for manga page file names (e.g. 001.webp < 002.webp < 010.webp)
    fun naturalCompare(s1: String, s2: String): Int {
        val regex = Regex("(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)")
        val p1 = s1.split(regex)
        val p2 = s2.split(regex)
        for (i in 0 until min(p1.size, p2.size)) {
            val n1 = p1[i].toLongOrNull()
            val n2 = p2[i].toLongOrNull()
            if (n1 != null && n2 != null) {
                val cmp = n1.compareTo(n2)
                if (cmp != 0) return cmp
            } else {
                val cmp = p1[i].compareTo(p2[i], ignoreCase = true)
                if (cmp != 0) return cmp
            }
        }
        return p1.size.compareTo(p2.size)
    }

    // Helper to inspect entries inside a CBZ/ZIP file and generate thumbnails
    fun inspectCbzFile(targetFile: File) {
        scope.launch {
            isExtractingPages = true
            extractionProgressText = "Opening ${targetFile.name}..."
            extractionProgress = 0f
            pageEntries = emptyList()
            selectedPageIndices.clear()

            val entries = withContext(Dispatchers.IO) {
                val list = mutableListOf<CbzPageEntry>()
                try {
                    val zip = ZipFile(targetFile)
                    val rawEntries = zip.entries().asSequence()
                        .filter { entry ->
                            if (entry.isDirectory) return@filter false
                            val name = entry.name.lowercase()
                            if (name.contains("__macosx") || name.startsWith(".")) return@filter false
                            name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp")
                        }
                        .sortedWith { a, b -> naturalCompare(a.name, b.name) }
                        .toList()

                    // Decode low-res thumbnails
                    val total = rawEntries.size
                    for ((idx, zEntry) in rawEntries.withIndex()) {
                        val thumb = try {
                            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                            zip.getInputStream(zEntry).use { stream ->
                                BitmapFactory.decodeStream(stream, null, opts)
                            }
                        } catch (_: Exception) {
                            null
                        }

                        val simpleName = zEntry.name.substringAfterLast("/")
                        list.add(
                            CbzPageEntry(
                                index = idx,
                                entryName = zEntry.name,
                                displayName = simpleName,
                                thumbnail = thumb
                            )
                        )
                        extractionProgress = (idx + 1).toFloat() / total
                    }
                    zip.close()
                } catch (e: Exception) {
                    Log.e("CbzBrowserSheet", "Error opening CBZ: ${e.message}")
                }
                list
            }

            pageEntries = entries
            // By default, pre-select the first 5 pages for rapid debugging, or all if short
            if (entries.size <= 5) {
                selectedPageIndices.addAll(entries.indices)
            } else {
                selectedPageIndices.addAll(0 until 5)
            }
            isExtractingPages = false
        }
    }

    // Load full or selected pages into studio
    fun executeLoadPages(indicesToLoad: List<Int>) {
        val targetFile = customArchiveFile ?: selectedArchive?.file ?: return
        if (indicesToLoad.isEmpty()) return

        scope.launch {
            isExtractingPages = true
            extractionProgress = 0f
            extractionProgressText = "Extracting ${indicesToLoad.size} pages from ${targetFile.name}..."

            val decodedPages = withContext(Dispatchers.IO) {
                val result = mutableListOf<Pair<String, Bitmap>>()
                try {
                    val zip = ZipFile(targetFile)
                    val total = indicesToLoad.size
                    for ((count, pageIdx) in indicesToLoad.withIndex()) {
                        val pEntry = pageEntries.getOrNull(pageIdx) ?: continue
                        val zEntry = zip.getEntry(pEntry.entryName) ?: continue

                        val bmp = zip.getInputStream(zEntry).use { stream ->
                            BitmapFactory.decodeStream(stream)
                        }
                        if (bmp != null) {
                            val mangaPrefix = targetFile.nameWithoutExtension
                                .replace("Chapter", "")
                                .replace(".cbz", "")
                                .ifBlank { targetFile.parentFile?.name ?: "Manga" }
                                .trim('_', ' ')
                            val pageLabel = "${mangaPrefix}_p${pageIdx + 1}"
                            result.add(pageLabel to bmp)
                        }
                        extractionProgress = (count + 1).toFloat() / total
                    }
                    zip.close()
                } catch (e: Exception) {
                    Log.e("CbzBrowserSheet", "Extraction error: ${e.message}", e)
                }
                result
            }

            isExtractingPages = false
            if (decodedPages.isNotEmpty()) {
                onLoadPages(decodedPages)
                onDismiss()
            }
        }
    }

    // Extract pages and immediately trigger sequential batch translation pipeline
    fun executeLoadAndTranslate(targetFile: File, indicesToLoad: List<Int>? = null) {
        scope.launch {
            isExtractingPages = true
            extractionProgress = 0f
            extractionProgressText = "Opening ${targetFile.name} for translation..."

            val metadata = withContext(Dispatchers.IO) {
                com.raen.crunchlab.data.CrunchExporter.extractCbzMetadata(targetFile)
            }

            val decodedPages = withContext(Dispatchers.IO) {
                val result = mutableListOf<Pair<String, Bitmap>>()
                try {
                    val zip = ZipFile(targetFile)
                    val rawEntries = metadata.imageEntryNames.mapNotNull { zip.getEntry(it) }

                    val targetEntriesWithIndices = if (indicesToLoad != null && indicesToLoad.isNotEmpty()) {
                        indicesToLoad.mapNotNull { idx -> rawEntries.getOrNull(idx)?.let { idx to it } }
                    } else {
                        rawEntries.mapIndexed { idx, entry -> idx to entry }
                    }

                    val total = targetEntriesWithIndices.size
                    for ((count, pair) in targetEntriesWithIndices.withIndex()) {
                        val (origIdx, zEntry) = pair
                        val bmp = zip.getInputStream(zEntry).use { stream ->
                            BitmapFactory.decodeStream(stream)
                        }
                        if (bmp != null) {
                            val mangaPrefix = metadata.mangaTitle
                                ?: targetFile.nameWithoutExtension
                                    .replace("Chapter", "")
                                    .replace(".cbz", "")
                                    .ifBlank { targetFile.parentFile?.name ?: "Manga" }
                                    .trim('_', ' ')
                            val pageLabel = "${mangaPrefix}_p${origIdx + 1}"
                            result.add(pageLabel to bmp)
                        }
                        extractionProgress = (count + 1).toFloat() / total
                    }
                    zip.close()
                } catch (e: Exception) {
                    Log.e("CbzBrowserSheet", "Extraction error: ${e.message}", e)
                }
                result
            }

            isExtractingPages = false
            if (decodedPages.isNotEmpty()) {
                val actualIndices = if (indicesToLoad != null && indicesToLoad.isNotEmpty()) {
                    indicesToLoad
                } else {
                    metadata.imageEntryNames.indices.toList()
                }
                onTranslateCbz(decodedPages, targetFile, metadata, actualIndices)
                onDismiss()
            }
        }
    }

    // Scan device storage for manga .cbz files
    fun scanDeviceArchives() {
        scope.launch {
            isScanningArchives = true
            val items = withContext(Dispatchers.IO) {
                val candidateRoots = listOfNotNull(
                    File("/sdcard/Aaaaa/Otaku/Komikku/downloads/NHentai (JA)"),
                    File("/sdcard/Aaaaa/Otaku/Komikku/downloads/NHentai (EN)"),
                    File("/sdcard/Aaaaa/Otaku/Komikku/downloads"),
                    File("/sdcard/Download/MangaDataset"),
                    File("/sdcard/Download/CrunchLab"),
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MangaDataset"),
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "")
                )

                val list = mutableListOf<CbzMangaItem>()
                val seenPaths = mutableSetOf<String>()

                fun scanDir(dir: File, depth: Int = 0) {
                    if (depth > 3 || !dir.exists() || !dir.isDirectory) return
                    val files = dir.listFiles() ?: return
                    for (f in files) {
                        if (f.isDirectory && !f.name.startsWith(".")) {
                            scanDir(f, depth + 1)
                        } else if (f.isFile) {
                            val ext = f.extension.lowercase()
                            if ((ext == "cbz" || ext == "zip") && f.length() > 500 * 1024L) {
                                if (seenPaths.add(f.absolutePath)) {
                                    val sizeMb = f.length() / (1024f * 1024f)
                                    val parentName = f.parentFile?.name ?: "Downloads"
                                    val title = if (f.name.equals("Chapter.cbz", ignoreCase = true) || f.name.equals("Chapter.zip", ignoreCase = true)) {
                                        parentName
                                    } else {
                                        f.nameWithoutExtension
                                    }
                                    list.add(CbzMangaItem(f, title, parentName, sizeMb))
                                }
                            }
                        }
                    }
                }

                for (root in candidateRoots) {
                    scanDir(root)
                }

                list.sortedBy { it.title }
            }

            availableArchives = items
            isScanningArchives = false

            // Auto-select first archive if available and inspect
            if (items.isNotEmpty() && selectedArchive == null && customArchiveFile == null) {
                selectedArchive = items[0]
                inspectCbzFile(items[0].file)
            }
        }
    }

    // System File Picker for custom .cbz
    val cbzPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                isExtractingPages = true
                extractionProgressText = "Copying chosen CBZ to cache..."
                val displayName = try {
                    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                } catch (_: Exception) { null } ?: "picked_manga.cbz"

                val cachedFile = withContext(Dispatchers.IO) {
                    try {
                        val input: InputStream? = context.contentResolver.openInputStream(uri)
                        val temp = File(context.cacheDir, displayName)
                        if (temp.exists()) temp.delete()
                        FileOutputStream(temp).use { out ->
                            input?.copyTo(out)
                        }
                        input?.close()
                        temp
                    } catch (e: Exception) {
                        Log.e("CbzBrowserSheet", "Error copying uri: ${e.message}")
                        null
                    }
                }
                if (cachedFile != null && cachedFile.length() > 0) {
                    customArchiveFile = cachedFile
                    selectedArchive = null
                    if (autoStartTranslate) {
                        executeLoadAndTranslate(cachedFile)
                    } else {
                        inspectCbzFile(cachedFile)
                    }
                } else {
                    isExtractingPages = false
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        scanDeviceArchives()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1A24),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color.Gray) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("📦 CBZ Manga Inspector", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("Pick archive & select specific pages or full chapter", color = Color.LightGray, fontSize = 11.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // System Picker Button
                    Surface(
                        color = Color(0xFF2979FF),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.clickable {
                            cbzPickerLauncher.launch(
                                arrayOf(
                                    "application/vnd.comicbook+zip",
                                    "application/zip",
                                    "application/x-cbz",
                                    "application/octet-stream",
                                    "*/*"
                                )
                            )
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Browse Files", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = null, tint = Color.Gray)
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Auto-translate toggle row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = autoStartTranslate,
                        onCheckedChange = { autoStartTranslate = it }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "Auto-translate on selection",
                        color = if (autoStartTranslate) Color(0xFF00E5FF) else Color.LightGray,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    "Exports to Komikku Local Source",
                    color = Color.Gray,
                    fontSize = 9.sp
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Archive Selector Carousel
            if (availableArchives.isNotEmpty()) {
                Text("Detected CBZ Manga on Device:", color = Color.Gray, fontSize = 10.sp)
                Spacer(modifier = Modifier.height(3.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(availableArchives) { item ->
                        val isSelected = selectedArchive == item && customArchiveFile == null
                        Surface(
                            color = if (isSelected) Color(0xFF7C4DFF) else Color(0xFF262636),
                            shape = RoundedCornerShape(6.dp),
                            border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFB388FF)) else null,
                            modifier = Modifier.clickable {
                                if (autoStartTranslate) {
                                    executeLoadAndTranslate(item.file)
                                } else {
                                    customArchiveFile = null
                                    selectedArchive = item
                                    inspectCbzFile(item.file)
                                }
                            }
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        item.title,
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    Surface(
                                        color = Color(0xFF00E5FF).copy(alpha = 0.25f),
                                        shape = RoundedCornerShape(3.dp),
                                        modifier = Modifier.clickable {
                                            executeLoadAndTranslate(item.file)
                                        }
                                    ) {
                                        Text(
                                            "⚡ Translate",
                                            color = Color(0xFF00E5FF),
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                                Text(
                                    "${String.format("%.1f", item.sizeMb)}MB · ${item.parentFolder}",
                                    color = if (isSelected) Color(0xFFFFD600) else Color.Gray,
                                    fontSize = 9.sp
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Current Active Manga Info & Page Selection Controls
            Surface(
                color = Color(0xFF20202E),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    val currentTitle = customArchiveFile?.name ?: selectedArchive?.title ?: "Select a Manga Archive"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(currentTitle, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text(
                                "Total: ${pageEntries.size} pages · Selected: ${selectedPageIndices.size}",
                                color = Color(0xFF00E5FF),
                                fontSize = 10.sp
                            )
                        }

                        // Selection Action Chips
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Surface(
                                color = Color(0xFF333344),
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.clickable {
                                    selectedPageIndices.clear()
                                    selectedPageIndices.addAll(pageEntries.indices)
                                }
                            ) {
                                Text("All", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
                            }
                            Surface(
                                color = Color(0xFF333344),
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.clickable {
                                    selectedPageIndices.clear()
                                    selectedPageIndices.addAll(0 until min(5, pageEntries.size))
                                }
                            ) {
                                Text("1-5", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
                            }
                            Surface(
                                color = Color(0xFF333344),
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.clickable {
                                    selectedPageIndices.clear()
                                }
                            ) {
                                Text("None", color = Color.LightGray, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
                            }
                        }
                    }
                }
            }

            // Extraction / Progress Bar
            AnimatedVisibility(visible = isExtractingPages) {
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    LinearProgressIndicator(
                        progress = { extractionProgress },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = Color(0xFF00E5FF),
                        trackColor = Color(0xFF262636)
                    )
                    Text(extractionProgressText, color = Color.LightGray, fontSize = 9.sp, modifier = Modifier.padding(top = 2.dp))
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Page Thumbnails Grid (3 columns)
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (pageEntries.isNotEmpty()) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(pageEntries) { idx, page ->
                            val isSelected = selectedPageIndices.contains(idx)

                            Surface(
                                color = if (isSelected) Color(0xFF1E2F40) else Color(0xFF222230),
                                shape = RoundedCornerShape(6.dp),
                                border = if (isSelected) androidx.compose.foundation.BorderStroke(2.dp, Color(0xFF00E5FF)) else androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF333344)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(0.72f)
                                    .clickable {
                                        if (isSelected) selectedPageIndices.remove(idx) else selectedPageIndices.add(idx)
                                    }
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    if (page.thumbnail != null) {
                                        Image(
                                            bitmap = page.thumbnail!!.asImageBitmap(),
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF282836)), contentAlignment = Alignment.Center) {
                                            Text("p.${idx + 1}", color = Color.Gray, fontSize = 12.sp)
                                        }
                                    }

                                    // Page Number Chip
                                    Surface(
                                        color = Color.Black.copy(alpha = 0.75f),
                                        shape = RoundedCornerShape(topStart = 0.dp, bottomEnd = 6.dp),
                                        modifier = Modifier.align(Alignment.TopStart)
                                    ) {
                                        Text(
                                            "p.${idx + 1}",
                                            color = Color.White,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }

                                    // Selection Checkbox Badge
                                    if (isSelected) {
                                        Surface(
                                            color = Color(0xFF00E5FF),
                                            shape = CircleShape,
                                            modifier = Modifier
                                                .size(18.dp)
                                                .align(Alignment.TopEnd)
                                                .padding(2.dp)
                                        ) {
                                            Icon(Icons.Filled.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(12.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else if (!isExtractingPages && !isScanningArchives) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No pages found in this archive", color = Color.Gray, fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Sticky Execution Action Buttons
            val targetFile = customArchiveFile ?: selectedArchive?.file
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 1. Translate Full Manga (Primary Action)
                Button(
                    onClick = {
                        if (targetFile != null) {
                            executeLoadAndTranslate(targetFile, null)
                        } else {
                            executeLoadPages(pageEntries.indices.toList())
                        }
                    },
                    enabled = (pageEntries.isNotEmpty() || targetFile != null) && !isExtractingPages,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6200EA)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1.2f).height(44.dp)
                ) {
                    Text("🚀 Translate Full CBZ (${pageEntries.size}p)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                // 2. Translate Selected Pages or Inspect in Studio
                if (selectedPageIndices.isNotEmpty() && selectedPageIndices.size < pageEntries.size) {
                    Button(
                        onClick = {
                            if (targetFile != null) {
                                executeLoadAndTranslate(targetFile, selectedPageIndices.sorted())
                            } else {
                                executeLoadPages(selectedPageIndices.sorted())
                            }
                        },
                        enabled = !isExtractingPages,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6D00)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f).height(44.dp)
                    ) {
                        Text("⚡ Translate (${selectedPageIndices.size}p)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    OutlinedButton(
                        onClick = {
                            executeLoadPages(pageEntries.indices.toList())
                        },
                        enabled = pageEntries.isNotEmpty() && !isExtractingPages,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(0.9f).height(44.dp)
                    ) {
                        Text("Inspect Studio", fontSize = 10.sp, color = Color.LightGray)
                    }
                }
            }
        }
    }
}
