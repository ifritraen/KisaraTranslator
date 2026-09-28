package com.raen.crunchlab.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class DatasetPageItem(
    val file: File,
    val name: String,
    val series: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatasetBrowserSheet(
    onDismiss: () -> Unit,
    onLoadPages: (List<File>) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var resolvedDir by remember { mutableStateOf<File?>(null) }
    val FEATURED_PAGES = remember {
        setOf(
            "aozora_no_xipuria_absentee_adm_001.png",
            "blue_archive_bluesechis_4_koma_001.jpg",
            "descent_001.jpg",
            "doctors_knife_001.jpg",
            "hyperborea_001.jpg",
            "izumino_001.png",
            "love_from_above_001.jpg",
            "maxed_out_001.jpg",
            "mnemosyne_001.png",
            "prince_akihiko_prince_aaa_001.png",
            "prince_aoihiko_001.png",
            "re__united_001.png",
            "shards_of_a_stained_reverie_001.jpg",
            "short_strip_001.png",
            "jp_manga_16_001.jpg",
            "jp_manga_17_001.png",
            "jp_manga_18_001.jpg",
            "jp_manga_20_001.jpg",
            "jp_manga_21_001.jpg",
            "jp_manga_22_001.png",
            "jp_manga_23_001.png",
            "jp_manga_24_001.png",
            "jp_manga_25_001.jpg",
            "jp_manga_26_001.png",
            "jp_manga_27_001.png",
            "jp_manga_28_001.jpg",
            "b4_c01_p008.webp",
            "b4_c02_p005.webp",
            "b4_c03_p004.webp",
            "b4_c04_p006.webp",
            "b4_c05_p007.webp",
            "b4_c06_p045.webp",
            "b4_c07_p013.webp",
            "b4_c08_p007.webp",
            "b4_c09_p002.webp",
            "b4_c10_p002.webp",
            "b4_c11_p002.webp",
            "b4_c12_p006.webp",
            "b4_c13_p018.jpg",
            "b4_c14_p008.webp",
            "b2_c01_p019.webp",
            "b2_c05_p022.webp",
            "b2_c10_p002.jpg",
            "b2_c12_p001.jpg",
            "b2_c13_p002.jpg",
            "b2_c14_p002.webp",
            "b2_c17_p022.png"
        )
    }

    var allPages by remember { mutableStateOf<List<DatasetPageItem>>(emptyList()) }
    var seriesList by remember { mutableStateOf<List<String>>(listOf("All", "⭐ 1 Per Manga")) }
    var selectedSeries by remember { mutableStateOf("All") }
    val selectedFiles = remember { mutableStateListOf<File>() }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val extRoot = Environment.getExternalStorageDirectory()
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val candidateDirs = listOfNotNull(
                File("/sdcard/Download/MangaDataset"),
                File("/sdcard/Download/MangaDataset/raw_pages"),
                File(downloadDir, "MangaDataset"),
                File(downloadDir, "MangaDataset/raw_pages"),
                File(extRoot, "Download/MangaDataset"),
                File(context.getExternalFilesDir(null), "dataset"),
                File(downloadDir, "CrunchLab/dataset"),
                File("/sdcard/Download/CrunchLab/dataset"),
                File("/storage/emulated/0/Download/CrunchLab/dataset"),
                File(context.filesDir, "dataset")
            )

            var foundFiles: List<File> = emptyList()
            var chosenDir: File? = null

            for (dir in candidateDirs) {
                if (dir.exists()) {
                    val list = dir.listFiles { f ->
                        val ext = f.extension.lowercase()
                        (ext == "jpg" || ext == "jpeg" || ext == "png" || ext == "webp") && !f.name.startsWith(".")
                    }?.toList() ?: emptyList()

                    if (list.isNotEmpty()) {
                        foundFiles = list.sortedBy { it.name }
                        chosenDir = dir
                        break
                    }
                }
            }

            val titlesMap = mutableMapOf<String, String>()
            if (chosenDir != null) {
                val idxFile = File(chosenDir, "dataset_index.json")
                if (idxFile.exists()) {
                    try {
                        val jsonStr = idxFile.readText()
                        val arr = org.json.JSONArray(jsonStr)
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            val fn = obj.optString("filename")
                            val title = obj.optString("title").ifBlank { obj.optString("series") }
                            if (fn.isNotBlank() && title.isNotBlank()) {
                                titlesMap[fn] = title
                            }
                        }
                    } catch (_: Exception) {}
                }
            }

            val items = foundFiles.map { file ->
                val series = titlesMap[file.name] ?: file.name.substringBeforeLast("_").ifBlank { "Manga" }
                DatasetPageItem(file, file.name, series)
            }
            val distinctSeries = listOf("⭐ 1 Per Manga", "All") + items.map { it.series }.distinct().sorted()

            withContext(Dispatchers.Main) {
                resolvedDir = chosenDir ?: candidateDirs[0]
                allPages = items
                seriesList = distinctSeries
                isLoading = false
            }
        }
    }

    val filteredPages = remember(allPages, selectedSeries) {
        when (selectedSeries) {
            "⭐ 1 Per Manga" -> allPages.filter { it.file.name in FEATURED_PAGES }
            "All" -> allPages
            else -> allPages.filter { it.series.equals(selectedSeries, ignoreCase = true) }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E2C),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color.Gray) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        "📁 Manga Benchmark Dataset",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${allPages.size} pages available in ${resolvedDir?.name ?: "dataset"}",
                        color = Color.LightGray,
                        fontSize = 12.sp
                    )
                }

                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Series Filter Chips Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (series in seriesList) {
                    val isSelected = (series == selectedSeries)
                    Surface(
                        color = if (isSelected) Color(0xFF2979FF) else Color(0xFF2E2E3E),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.clickable { selectedSeries = series }
                    ) {
                        Text(
                            text = series,
                            color = if (isSelected) Color.White else Color.LightGray,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Selection Controls Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            selectedFiles.clear()
                            selectedFiles.addAll(filteredPages.map { it.file })
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("Select All (${filteredPages.size})", fontSize = 12.sp, color = Color(0xFF00E5FF))
                    }

                    if (selectedFiles.isNotEmpty()) {
                        TextButton(
                            onClick = { selectedFiles.clear() },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Deselect", fontSize = 12.sp, color = Color.Gray)
                        }
                    }
                }

                Button(
                    onClick = {
                        val filesToLoad = selectedFiles.toList()
                        onLoadPages(filesToLoad)
                        onDismiss()
                    },
                    enabled = selectedFiles.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C853)),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        "Load (${selectedFiles.size})",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Pages Grid with Previews
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF2979FF))
                }
            } else if (filteredPages.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No pages found in dataset folder", color = Color.Gray)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredPages, key = { it.file.absolutePath }) { page ->
                        val isSelected = selectedFiles.contains(page.file)
                        DatasetThumbnailCard(
                            item = page,
                            isSelected = isSelected,
                            onToggleSelect = {
                                if (isSelected) {
                                    selectedFiles.remove(page.file)
                                } else {
                                    selectedFiles.add(page.file)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DatasetThumbnailCard(
    item: DatasetPageItem,
    isSelected: Boolean,
    onToggleSelect: () -> Unit
) {
    var thumbnail by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(item.file) {
        withContext(Dispatchers.IO) {
            val opts = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(item.file.absolutePath, opts)
            opts.inSampleSize = calculateInSampleSize(opts, 200, 300)
            opts.inJustDecodeBounds = false
            val bmp = BitmapFactory.decodeFile(item.file.absolutePath, opts)
            withContext(Dispatchers.Main) {
                thumbnail = bmp
            }
        }
    }

    Box(
        modifier = Modifier
            .aspectRatio(0.70f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF282838))
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) Color(0xFF00E5FF) else Color(0xFF3E3E50),
                shape = RoundedCornerShape(8.dp)
            )
            .clickable { onToggleSelect() }
    ) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail!!.asImageBitmap(),
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("Loading...", color = Color.DarkGray, fontSize = 10.sp)
            }
        }

        // Selection Checkbox Badge
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(22.dp)
                .background(
                    color = if (isSelected) Color(0xFF00E5FF) else Color.Black.copy(alpha = 0.5f),
                    shape = CircleShape
                )
                .border(1.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        // Bottom Label Bar
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.75f))
                .padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            Text(
                text = item.name,
                color = Color.White,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    val height = options.outHeight
    val width = options.outWidth
    var inSampleSize = 1

    if (height > reqHeight || width > reqWidth) {
        val halfHeight = height / 2
        val halfWidth = width / 2
        while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}
