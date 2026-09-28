package com.raen.method3.annotator.ui

import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raen.method3.annotator.data.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnotatorScreen(
    initialServerIp: String = "192.168.0.237",
    initialPort: String = "8000",
) {
    val scope = rememberCoroutineScope()
    val api = remember { AnnotatorApiClient() }

    var serverIp by remember { mutableStateOf(initialServerIp) }
    var serverPort by remember { mutableStateOf(initialPort) }
    val serverUrl = remember(serverIp, serverPort) { "http://$serverIp:$serverPort" }

    var activeBatch by remember { mutableStateOf("batch_01") }
    var serverStatus by remember { mutableStateOf<ServerStatus?>(null) }
    var isConnected by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }

    // Queue & Mode Management
    var queueMode by remember { mutableStateOf(AppQueueMode.ANNOTATE) }
    var isMinimalMode by remember { mutableStateOf(true) }

    // Current active bubble (Annotate Queue)
    var currentBubble by remember { mutableStateOf<BubbleItem?>(null) }
    var currentBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // Current unverified bubble (Verify Queue)
    var currentUnverified by remember { mutableStateOf<UnverifiedBubbleItem?>(null) }

    // Annotation state
    var phase by remember { mutableStateOf(AnnotationPhase.DECISION) }
    val crunchPoints = remember { mutableStateListOf<CrunchPointAnnotation>() }
    var selectedPointIndex by remember { mutableStateOf<Int?>(null) }
    val dividingLines = remember { mutableStateListOf<DividingLineAnnotation>() }

    // Auto-suggest preview & edit state in Minimal Mode
    var isAutoSuggestPreview by remember { mutableStateOf(false) }
    var isAutoSuggestLoading by remember { mutableStateOf(false) }
    var isEditingAutoSuggest by remember { mutableStateOf(false) }
    var autoSuggestFailed by remember { mutableStateOf(false) }

    // UI state
    val snackbarHostState = remember { SnackbarHostState() }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showProgressDialog by remember { mutableStateOf(false) }

    // Candidate points from OpenCV auto-suggest
    val candidatePoints = remember { mutableStateListOf<Point>() }

    // Review Mode state
    val reviewList = remember { mutableStateListOf<String>() }
    var reviewCurrentIndex by remember { mutableStateOf(0) }
    var reviewOnlyConjoined by remember { mutableStateOf(true) }
    var currentVerified by remember { mutableStateOf<VerifiedBubbleItem?>(null) }

    // Singles Recheck Mode state
    val singlesList = remember { mutableStateListOf<BubbleThumbItem>() }
    var singlesCurrentIndex by remember { mutableIntStateOf(0) }
    var singlesViewMode by remember { mutableStateOf("grid") } // "grid" or "single"
    var singlesGridColumns by remember { mutableIntStateOf(3) } // 2, 3, 4

    // Helper to refresh server status
    fun refreshStatus() {
        scope.launch {
            val res = api.fetchStatus(serverUrl, activeBatch)
            res.onSuccess { status ->
                serverStatus = status
                isConnected = true
                if (status.batches.isNotEmpty() && !status.batches.contains(activeBatch)) {
                    activeBatch = status.batches.first()
                }
            }.onFailure {
                isConnected = false
                statusMessage = "Cannot connect: ${it.message}"
            }
        }
    }

    // Load next in Annotate Queue
    fun loadNextAnnotate() {
        isLoading = true
        crunchPoints.clear()
        selectedPointIndex = null
        dividingLines.clear()
        candidatePoints.clear()
        phase = AnnotationPhase.DECISION
        isAutoSuggestPreview = false
        isAutoSuggestLoading = false
        isEditingAutoSuggest = false
        autoSuggestFailed = false

        scope.launch {
            refreshStatus()
            val res = api.fetchNextBubble(serverUrl, activeBatch)
            res.onSuccess { item ->
                currentBubble = item
                if (item != null) {
                    val bRes = api.fetchBitmap(item.imageUrl)
                    bRes.onSuccess { currentBitmap = it }
                        .onFailure { statusMessage = "Image load error: ${it.message}" }
                } else {
                    currentBitmap = null
                }
                isLoading = false
            }.onFailure {
                isLoading = false
                statusMessage = "Next bubble error: ${it.message}"
            }
        }
    }

    // Load next in Verify Queue
    fun loadNextVerify() {
        isLoading = true
        crunchPoints.clear()
        selectedPointIndex = null
        dividingLines.clear()
        candidatePoints.clear()
        isAutoSuggestPreview = false
        isAutoSuggestLoading = false

        scope.launch {
            refreshStatus()
            val res = api.fetchNextUnverified(serverUrl, activeBatch)
            res.onSuccess { item ->
                currentUnverified = item
                if (item != null) {
                    crunchPoints.addAll(item.crunchPoints)
                    dividingLines.addAll(item.dividingLines)
                    val bRes = api.fetchBitmap(item.imageUrl)
                    bRes.onSuccess { currentBitmap = it }
                        .onFailure { statusMessage = "Image load error: ${it.message}" }
                } else {
                    currentBitmap = null
                }
                isLoading = false
            }.onFailure {
                isLoading = false
                statusMessage = "Verify queue error: ${it.message}"
            }
        }
    }

    // Load specific verified bubble in Review Mode
    fun loadReviewItem(bubbleId: String) {
        isLoading = true
        crunchPoints.clear()
        selectedPointIndex = null
        dividingLines.clear()
        candidatePoints.clear()
        phase = AnnotationPhase.CRUNCH_POINTS
        isEditingAutoSuggest = true
        isAutoSuggestPreview = true

        scope.launch {
            val res = api.fetchVerifiedBubble(serverUrl, activeBatch, bubbleId)
            res.onSuccess { item ->
                currentVerified = item
                crunchPoints.addAll(item.crunchPoints)
                dividingLines.addAll(item.dividingLines)
                val bRes = api.fetchBitmap(item.imageUrl)
                bRes.onSuccess { currentBitmap = it }
                    .onFailure { statusMessage = "Image load error: ${it.message}" }

                // Fetch candidate points to enable snapping/dots
                val autoRes = api.autoSuggest(serverUrl, activeBatch, bubbleId)
                autoRes.onSuccess { sRes ->
                    candidatePoints.clear()
                    candidatePoints.addAll(sRes.candidatePoints)
                }
                isLoading = false
            }.onFailure {
                isLoading = false
                statusMessage = "Load bubble error: ${it.message}"
            }
        }
    }

    // Load list of verified bubbles in Review Mode
    fun loadReviewList() {
        isLoading = true
        scope.launch {
            refreshStatus()
            val res = api.fetchVerifiedList(serverUrl, activeBatch, reviewOnlyConjoined)
            res.onSuccess { list ->
                reviewList.clear()
                reviewList.addAll(list)
                reviewCurrentIndex = 0
                if (list.isNotEmpty()) {
                    loadReviewItem(list[0])
                } else {
                    isLoading = false
                    currentBitmap = null
                    currentVerified = null
                }
            }.onFailure {
                isLoading = false
                statusMessage = "Load review list error: ${it.message}"
            }
        }
    }

    fun reviewPrev() {
        if (reviewCurrentIndex > 0) {
            reviewCurrentIndex--
            loadReviewItem(reviewList[reviewCurrentIndex])
        }
    }

    fun reviewNext() {
        if (reviewCurrentIndex < reviewList.size - 1) {
            reviewCurrentIndex++
            loadReviewItem(reviewList[reviewCurrentIndex])
        }
    }

    fun saveReviewUpdate(isConjoined: Boolean) {
        val verified = currentVerified ?: return
        scope.launch {
            api.submitAnnotation(
                baseUrl = serverUrl,
                batch = activeBatch,
                bubbleId = verified.bubbleId,
                isConjoined = isConjoined,
                crunchPoints = if (isConjoined) crunchPoints.toList() else emptyList(),
                dividingLines = if (isConjoined) dividingLines.toList() else emptyList()
            )
            snackbarHostState.showSnackbar("Saved ${verified.bubbleId}")
            refreshStatus()
            if (reviewCurrentIndex < reviewList.size - 1) {
                reviewNext()
            }
        }
    }

    fun addCut() {
        if (crunchPoints.size < 12) { // Allow up to 6 cuts (12 notches)
            val currentCutNum = (crunchPoints.size / 2) + 1
            val usedCenters = crunchPoints.map { it.center }
            val unusedCandidates = candidatePoints.filter { cand ->
                usedCenters.none { uc -> kotlin.math.hypot((cand.x - uc.x).toFloat(), (cand.y - uc.y).toFloat()) < 25f }
            }

            val ptA: Point
            val ptB: Point
            val bmp = currentBitmap
            val bw = bmp?.width ?: 300
            val bh = bmp?.height ?: 300

            if (unusedCandidates.size >= 2) {
                ptA = unusedCandidates[0]
                ptB = unusedCandidates[1]
            } else if (unusedCandidates.size == 1) {
                ptA = unusedCandidates[0]
                ptB = Point((bw - ptA.x).coerceIn(20, bw - 20), ptA.y)
            } else {
                // Stagger default positions if multiple cuts are added
                val frac = (0.25f + (currentCutNum - 1) * 0.20f).coerceIn(0.15f, 0.85f)
                ptA = Point((bw * 0.20f).toInt(), (bh * frac).toInt())
                ptB = Point((bw * 0.80f).toInt(), (bh * frac).toInt())
            }

            val boxSize = 32
            val cpA = CrunchPointAnnotation(
                id = crunchPoints.size + 1,
                label = "crunch_a$currentCutNum",
                rect = Rect(ptA.x - boxSize / 2, ptA.y - boxSize / 2, ptA.x + boxSize / 2, ptA.y + boxSize / 2),
                center = ptA
            )
            val cpB = CrunchPointAnnotation(
                id = crunchPoints.size + 2,
                label = "crunch_b$currentCutNum",
                rect = Rect(ptB.x - boxSize / 2, ptB.y - boxSize / 2, ptB.x + boxSize / 2, ptB.y + boxSize / 2),
                center = ptB
            )

            crunchPoints.add(cpA)
            crunchPoints.add(cpB)

            val dl = DividingLineAnnotation(lineId = currentCutNum, points = listOf(ptA, ptB))
            dividingLines.add(dl)
        }
    }

    fun removeLastCut() {
        if (crunchPoints.size > 2) {
            // Remove last pair of crunch points
            crunchPoints.removeAt(crunchPoints.lastIndex)
            crunchPoints.removeAt(crunchPoints.lastIndex)
        }
        if (dividingLines.size > 1) {
            dividingLines.removeAt(dividingLines.lastIndex)
        }
    }

    // ==========================================
    // SINGLES RECHECK QUEUE METHODS
    // ==========================================
    fun loadSinglesItem(bubbleId: String) {
        isLoading = true
        crunchPoints.clear()
        selectedPointIndex = null
        dividingLines.clear()
        candidatePoints.clear()
        phase = AnnotationPhase.CRUNCH_POINTS
        isEditingAutoSuggest = true
        isAutoSuggestPreview = true

        scope.launch {
            val res = api.fetchVerifiedBubble(serverUrl, activeBatch, bubbleId)
            res.onSuccess { item ->
                currentVerified = item
                crunchPoints.addAll(item.crunchPoints)
                dividingLines.addAll(item.dividingLines)
                val bRes = api.fetchBitmap(item.imageUrl)
                bRes.onSuccess { currentBitmap = it }
                    .onFailure { statusMessage = "Image load error: ${it.message}" }

                val autoRes = api.autoSuggest(serverUrl, activeBatch, bubbleId)
                autoRes.onSuccess { sRes ->
                    candidatePoints.clear()
                    candidatePoints.addAll(sRes.candidatePoints)
                    if (crunchPoints.isEmpty() && sRes.success && sRes.crunchPoints.isNotEmpty()) {
                        crunchPoints.addAll(sRes.crunchPoints)
                        dividingLines.addAll(sRes.dividingLines)
                    }
                }
                isLoading = false
            }.onFailure {
                isLoading = false
                statusMessage = "Load bubble error: ${it.message}"
            }
        }
    }

    fun loadSinglesList() {
        isLoading = true
        scope.launch {
            refreshStatus()
            val res = api.fetchSinglesList(serverUrl, activeBatch)
            res.onSuccess { list ->
                singlesList.clear()
                singlesList.addAll(list)
                if (singlesCurrentIndex !in list.indices) {
                    singlesCurrentIndex = 0
                }
                if (singlesViewMode == "single" && list.isNotEmpty()) {
                    loadSinglesItem(list[singlesCurrentIndex].bubbleId)
                } else {
                    isLoading = false
                }
            }.onFailure {
                isLoading = false
                statusMessage = "Load singles list error: ${it.message}"
            }
        }
    }

    fun singlesPrev() {
        if (singlesCurrentIndex > 0) {
            singlesCurrentIndex--
            loadSinglesItem(singlesList[singlesCurrentIndex].bubbleId)
        }
    }

    fun singlesNext() {
        if (singlesCurrentIndex < singlesList.size - 1) {
            singlesCurrentIndex++
            loadSinglesItem(singlesList[singlesCurrentIndex].bubbleId)
        }
    }

    fun quickMarkConjoined(index: Int) {
        if (index !in singlesList.indices) return
        val item = singlesList[index]
        scope.launch {
            val autoRes = api.autoSuggest(serverUrl, activeBatch, item.bubbleId)
            var pts = emptyList<CrunchPointAnnotation>()
            var lines = emptyList<DividingLineAnnotation>()
            autoRes.onSuccess {
                if (it.success && it.crunchPoints.isNotEmpty()) {
                    pts = it.crunchPoints
                    lines = it.dividingLines
                }
            }
            api.submitAnnotation(
                baseUrl = serverUrl,
                batch = activeBatch,
                bubbleId = item.bubbleId,
                isConjoined = true,
                crunchPoints = pts,
                dividingLines = lines,
                notes = "reclassified_from_singles_quick"
            )
            singlesList[index] = item.copy(isConjoined = true)
            snackbarHostState.showSnackbar("Marked ${item.bubbleId} as Conjoined ✓")
            refreshStatus()
        }
    }

    fun saveSinglesConjoined(isConjoined: Boolean) {
        if (singlesCurrentIndex !in singlesList.indices) return
        val item = singlesList[singlesCurrentIndex]
        scope.launch {
            api.submitAnnotation(
                baseUrl = serverUrl,
                batch = activeBatch,
                bubbleId = item.bubbleId,
                isConjoined = isConjoined,
                crunchPoints = if (isConjoined) crunchPoints.toList() else emptyList(),
                dividingLines = if (isConjoined) dividingLines.toList() else emptyList(),
                notes = if (isConjoined) "reclassified_from_singles" else ""
            )
            singlesList[singlesCurrentIndex] = item.copy(isConjoined = isConjoined)
            currentVerified = currentVerified?.copy(isConjoined = isConjoined)
            snackbarHostState.showSnackbar("Saved ${item.bubbleId} (${if (isConjoined) "Conjoined" else "Single"})")
            refreshStatus()
            if (singlesCurrentIndex < singlesList.size - 1) {
                singlesNext()
            }
        }
    }

    // Switch between queues
    fun switchQueue(newQueue: AppQueueMode) {
        queueMode = newQueue
        if (newQueue == AppQueueMode.ANNOTATE) {
            loadNextAnnotate()
        } else if (newQueue == AppQueueMode.VERIFY) {
            loadNextVerify()
        } else if (newQueue == AppQueueMode.REVIEW) {
            loadReviewList()
        } else if (newQueue == AppQueueMode.SINGLES) {
            loadSinglesList()
        }
    }

    // Initial load
    LaunchedEffect(serverUrl) {
        refreshStatus()
        loadNextAnnotate()
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                snackbar = { data ->
                    Snackbar(
                        snackbarData = data,
                        containerColor = Color(0xFF262630),
                        contentColor = Color.White,
                        actionColor = Color(0xFF00E5FF),
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            )
        },
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        // Connection Dot
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (isConnected) Color(0xFF00E676) else Color(0xFFFF1744))
                        )

                        // Batch Switcher Chip (cycles available batches, e.g. batch_01 <-> batch_02)
                        FilterChip(
                            selected = true,
                            onClick = {
                                val batches = serverStatus?.batches ?: listOf("batch_01", "batch_02", "batch_03")
                                val currentIndex = batches.indexOf(activeBatch)
                                val nextIndex = if (currentIndex >= 0) (currentIndex + 1) % batches.size else 0
                                activeBatch = batches[nextIndex]
                                refreshStatus()
                                if (queueMode == AppQueueMode.ANNOTATE) {
                                    loadNextAnnotate()
                                } else if (queueMode == AppQueueMode.REVIEW) {
                                    loadReviewList()
                                } else if (queueMode == AppQueueMode.SINGLES) {
                                    loadSinglesList()
                                }
                            },
                            label = {
                                Text(activeBatch, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF1976D2),
                                selectedLabelColor = Color.White
                            )
                        )

                        // Queue Switcher: Annotate vs Verify
                        FilterChip(
                            selected = queueMode == AppQueueMode.ANNOTATE,
                            onClick = { if (queueMode != AppQueueMode.ANNOTATE) switchQueue(AppQueueMode.ANNOTATE) },
                            label = {
                                val unlab = serverStatus?.total?.minus(serverStatus?.annotated ?: 0) ?: 0
                                Text("Annotate", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF0097A7),
                                selectedLabelColor = Color.White
                            )
                        )

                        FilterChip(
                            selected = queueMode == AppQueueMode.VERIFY,
                            onClick = { if (queueMode != AppQueueMode.VERIFY) switchQueue(AppQueueMode.VERIFY) },
                            label = {
                                val unv = serverStatus?.unverified ?: 0
                                Text("Verify ($unv)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF7B1FA2),
                                selectedLabelColor = Color.White
                            )
                        )

                        FilterChip(
                            selected = queueMode == AppQueueMode.REVIEW,
                            onClick = { if (queueMode != AppQueueMode.REVIEW) switchQueue(AppQueueMode.REVIEW) },
                            label = {
                                val conjCount = serverStatus?.conjoined ?: 134
                                Text("Review ($conjCount)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFE65100),
                                selectedLabelColor = Color.White
                            )
                        )

                        if (queueMode == AppQueueMode.REVIEW) {
                            FilterChip(
                                selected = reviewOnlyConjoined,
                                onClick = {
                                    reviewOnlyConjoined = !reviewOnlyConjoined
                                    loadReviewList()
                                },
                                label = {
                                    Text(
                                        if (reviewOnlyConjoined) "Conjoined (${serverStatus?.conjoined ?: 134})" else "All (${serverStatus?.verified ?: 1662})",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFF00838F),
                                    selectedLabelColor = Color.White
                                )
                            )
                        }

                        // Singles Recheck Chip
                        FilterChip(
                            selected = queueMode == AppQueueMode.SINGLES,
                            onClick = { if (queueMode != AppQueueMode.SINGLES) switchQueue(AppQueueMode.SINGLES) },
                            label = {
                                val sCount = serverStatus?.single ?: 0
                                Text("Singles ($sCount)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF00897B),
                                selectedLabelColor = Color.White
                            )
                        )

                        if (queueMode == AppQueueMode.SINGLES) {
                            FilterChip(
                                selected = singlesViewMode == "grid",
                                onClick = {
                                    singlesViewMode = if (singlesViewMode == "grid") "single" else "grid"
                                    if (singlesViewMode == "single" && singlesList.isNotEmpty()) {
                                        loadSinglesItem(singlesList[singlesCurrentIndex].bubbleId)
                                    }
                                },
                                label = {
                                    Text(if (singlesViewMode == "grid") "Grid" else "1-by-1", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                },
                                leadingIcon = {
                                    Icon(
                                        if (singlesViewMode == "grid") Icons.Default.GridView else Icons.Default.ViewAgenda,
                                        contentDescription = null,
                                        modifier = Modifier.size(13.dp)
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFF004D40),
                                    selectedLabelColor = Color.White
                                )
                            )

                            if (singlesViewMode == "grid") {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    listOf(2, 3, 4).forEach { col ->
                                        Surface(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .clickable { singlesGridColumns = col },
                                            color = if (singlesGridColumns == col) Color(0xFF00E5FF) else Color(0xFF262634),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                text = "${col}C",
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (singlesGridColumns == col) Color.Black else Color.White,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Mode Switcher (Minimal vs Manual) - only in Annotate Queue
                        if (queueMode == AppQueueMode.ANNOTATE) {
                            Surface(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { isMinimalMode = !isMinimalMode },
                                color = if (isMinimalMode) Color(0xFF1B5E20) else Color(0xFF37474F),
                                border = BorderStroke(1.dp, if (isMinimalMode) Color(0xFF4CAF50) else Color(0xFF78909C))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        if (isMinimalMode) Icons.Default.Bolt else Icons.Default.Edit,
                                        contentDescription = "Mode",
                                        tint = Color.White,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Text(
                                        if (isMinimalMode) "Minimal Mode" else "Manual Mode",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }
                        }

                        // Progress Badge (Clickable -> Opens Mini Window)
                        serverStatus?.let { s ->
                            Surface(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { showProgressDialog = true },
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFF24242E),
                                border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.5f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(Icons.Default.BarChart, contentDescription = "Stats", tint = Color(0xFF00E5FF), modifier = Modifier.size(13.dp))
                                    Text(
                                        text = "${s.batchDone}/${s.total} (${s.batchLeft} left)",
                                        fontSize = 11.sp,
                                        color = Color(0xFF00E5FF),
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                },
                actions = {
                    // Stats / Progress Mini Window Button
                    IconButton(onClick = { showProgressDialog = true }) {
                        Icon(Icons.Default.Analytics, contentDescription = "Dataset Stats", tint = Color(0xFF00E5FF))
                    }

                    // Discard Image Button (Instant discard with Snackbar Undo)
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                val bubble = currentBubble ?: return@clickable
                                scope.launch {
                                    api.discardBubble(serverUrl, activeBatch, bubble.bubbleId, "discarded")
                                    loadNextAnnotate()
                                    val action = snackbarHostState.showSnackbar(
                                        message = "Discarded ${bubble.bubbleId}",
                                        actionLabel = "UNDO",
                                        duration = SnackbarDuration.Short
                                    )
                                    if (action == SnackbarResult.ActionPerformed) {
                                        api.undoLast(serverUrl)
                                        loadNextAnnotate()
                                    }
                                }
                            },
                        color = Color(0x33FF1744),
                        border = BorderStroke(1.dp, Color(0xFFFF5252))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Default.Block, contentDescription = "Discard Image", tint = Color(0xFFFF5252), modifier = Modifier.size(15.dp))
                            Text("Discard", fontSize = 11.sp, color = Color(0xFFFF8A80), fontWeight = FontWeight.Bold)
                        }
                    }

                    // Settings Button
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color(0xFFB0B0C0))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF181820)
                )
            )
        },
        bottomBar = {
            Surface(
                color = Color(0xFF181820),
                tonalElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (queueMode == AppQueueMode.SINGLES) {
                        // ==========================================
                        // QUEUE 4: SINGLES RECHECK CONTROLS
                        // ==========================================
                        if (singlesViewMode == "grid") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(Icons.Default.GridView, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(16.dp))
                                    Text(
                                        text = "${singlesList.size} singles • Tap card to inspect or '➔ Conj'",
                                        fontSize = 11.5.sp,
                                        color = Color(0xFFE0E0E0),
                                        fontWeight = FontWeight.Medium
                                    )
                                }

                                OutlinedButton(
                                    onClick = {
                                        singlesViewMode = "single"
                                        if (singlesList.isNotEmpty()) {
                                            loadSinglesItem(singlesList[singlesCurrentIndex].bubbleId)
                                        }
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.height(36.dp)
                                ) {
                                    Icon(Icons.Default.ViewAgenda, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("1-by-1 Mode", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        } else {
                            // Single-Item Mode in Singles Recheck
                            if (singlesList.isNotEmpty() && singlesCurrentIndex in singlesList.indices) {
                                val sItem = singlesList[singlesCurrentIndex]
                                val isConj = currentVerified?.isConjoined ?: sItem.isConjoined
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "#${singlesCurrentIndex + 1}/${singlesList.size} • ${sItem.bubbleId.substringAfterLast('_')}",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )

                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // Back to Grid
                                        OutlinedButton(
                                            onClick = { singlesViewMode = "grid" },
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier.height(32.dp)
                                        ) {
                                            Icon(Icons.Default.GridView, contentDescription = "Grid", modifier = Modifier.size(14.dp))
                                            Spacer(Modifier.width(3.dp))
                                            Text("Grid", fontSize = 11.sp)
                                        }

                                        AssistChip(
                                            onClick = { saveSinglesConjoined(!isConj) },
                                            label = {
                                                Text(
                                                    if (isConj) "Conjoined ✓ (➔ Single)" else "Single (➔ Conjoined)",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            },
                                            colors = AssistChipDefaults.assistChipColors(
                                                containerColor = if (isConj) Color(0xFF00695C) else Color(0xFF37474F),
                                                labelColor = Color.White
                                            )
                                        )
                                    }
                                }

                                Spacer(Modifier.height(6.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Prev Button
                                    OutlinedButton(
                                        onClick = { singlesPrev() },
                                        enabled = singlesCurrentIndex > 0,
                                        modifier = Modifier.weight(0.85f).height(48.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 6.dp)
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Prev", modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(2.dp))
                                        Text("Prev", fontSize = 12.sp)
                                    }

                                    // Auto-Suggest Button
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                val autoRes = api.autoSuggest(serverUrl, activeBatch, sItem.bubbleId)
                                                autoRes.onSuccess { res ->
                                                    candidatePoints.clear()
                                                    candidatePoints.addAll(res.candidatePoints)
                                                    if (res.success && res.crunchPoints.isNotEmpty()) {
                                                        crunchPoints.clear()
                                                        crunchPoints.addAll(res.crunchPoints)
                                                        dividingLines.clear()
                                                        dividingLines.addAll(res.dividingLines)
                                                    }
                                                }
                                            }
                                        },
                                        modifier = Modifier.weight(1.0f).height(48.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E5FF))
                                    ) {
                                        Icon(Icons.Default.Bolt, contentDescription = "Auto-Suggest", modifier = Modifier.size(15.dp))
                                        Spacer(Modifier.width(2.dp))
                                        Text("Auto", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                    }

                                    if (isConj) {
                                        val currentCutCount = crunchPoints.size / 2
                                        if (currentCutCount > 1) {
                                            OutlinedButton(
                                                onClick = { removeLastCut() },
                                                modifier = Modifier.weight(0.75f).height(48.dp),
                                                shape = RoundedCornerShape(8.dp),
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF8A80)),
                                                contentPadding = PaddingValues(horizontal = 4.dp)
                                            ) {
                                                Text("- Cut", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }

                                        if (currentCutCount < 6) {
                                            OutlinedButton(
                                                onClick = { addCut() },
                                                modifier = Modifier.weight(0.95f).height(48.dp),
                                                shape = RoundedCornerShape(8.dp),
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E5FF)),
                                                contentPadding = PaddingValues(horizontal = 4.dp)
                                            ) {
                                                Icon(Icons.Default.Add, contentDescription = "Add Cut", modifier = Modifier.size(14.dp))
                                                Spacer(Modifier.width(1.dp))
                                                Text("+ Cut ${currentCutCount + 1}", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }

                                        // Save Conjoined Button
                                        Button(
                                            onClick = { saveSinglesConjoined(true) },
                                            modifier = Modifier.weight(1.3f).height(48.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Icon(Icons.Default.Check, contentDescription = "Save", modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Save Conj ✓", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        }
                                    } else {
                                        // Mark Conjoined Button
                                        Button(
                                            onClick = {
                                                saveSinglesConjoined(true)
                                            },
                                            modifier = Modifier.weight(1.3f).height(48.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00897B)),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text("➔ Conjoined", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        }
                                    }

                                    // Next Button
                                    OutlinedButton(
                                        onClick = { singlesNext() },
                                        enabled = singlesCurrentIndex < singlesList.size - 1,
                                        modifier = Modifier.weight(0.85f).height(48.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 6.dp)
                                    ) {
                                        Text("Next", fontSize = 12.sp)
                                        Spacer(Modifier.width(2.dp))
                                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next", modifier = Modifier.size(16.dp))
                                    }
                                }
                            } else {
                                Text(
                                    text = "No singles loaded. Tap to refresh.",
                                    fontSize = 13.sp,
                                    color = Color.White,
                                    modifier = Modifier.padding(vertical = 12.dp)
                                )
                            }
                        }
                    } else if (queueMode == AppQueueMode.REVIEW) {
                        // ==========================================
                        // QUEUE 3: REVIEW VERIFIED ANNOTATIONS
                        // ==========================================
                        val vItem = currentVerified
                        if (vItem != null && reviewList.isNotEmpty()) {
                            val isConj = vItem.isConjoined
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "#${reviewCurrentIndex + 1}/${reviewList.size} • ${vItem.bubbleId.replace("${activeBatch}_", "")}",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                AssistChip(
                                    onClick = { saveReviewUpdate(!isConj) },
                                    label = {
                                        Text(
                                            if (isConj) "Conjoined ✓ (➔ Single)" else "Single (➔ Conjoined)",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = if (isConj) Color(0xFF00695C) else Color(0xFF37474F),
                                        labelColor = Color.White
                                    )
                                )
                            }

                            Spacer(Modifier.height(6.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Prev Button
                                OutlinedButton(
                                    onClick = { reviewPrev() },
                                    enabled = reviewCurrentIndex > 0,
                                    modifier = Modifier.weight(0.85f).height(48.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 6.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Prev", modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(2.dp))
                                    Text("Prev", fontSize = 12.sp)
                                }

                                if (isConj) {
                                    val currentCutCount = crunchPoints.size / 2
                                    if (currentCutCount > 1) {
                                        OutlinedButton(
                                            onClick = { removeLastCut() },
                                            modifier = Modifier.weight(0.75f).height(48.dp),
                                            shape = RoundedCornerShape(8.dp),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF8A80)),
                                            contentPadding = PaddingValues(horizontal = 4.dp)
                                        ) {
                                            Text("- Cut", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }

                                    if (currentCutCount < 6) {
                                        OutlinedButton(
                                            onClick = { addCut() },
                                            modifier = Modifier.weight(0.95f).height(48.dp),
                                            shape = RoundedCornerShape(8.dp),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E5FF)),
                                            contentPadding = PaddingValues(horizontal = 4.dp)
                                        ) {
                                            Icon(Icons.Default.Add, contentDescription = "Add Cut", modifier = Modifier.size(14.dp))
                                            Spacer(Modifier.width(1.dp))
                                            Text("+ Cut ${currentCutCount + 1}", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }

                                    // Save Update Button
                                    Button(
                                        onClick = { saveReviewUpdate(true) },
                                        modifier = Modifier.weight(1.3f).height(48.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Icon(Icons.Default.Check, contentDescription = "Save", modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Save ✓", fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                                    }
                                }

                                // Next Button
                                OutlinedButton(
                                    onClick = { reviewNext() },
                                    enabled = reviewCurrentIndex < reviewList.size - 1,
                                    modifier = Modifier.weight(0.85f).height(48.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 6.dp)
                                ) {
                                    Text("Next", fontSize = 12.sp)
                                    Spacer(Modifier.width(2.dp))
                                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next", modifier = Modifier.size(16.dp))
                                }
                            }
                        } else {
                            Text(
                                text = "No verified items found matching filter",
                                fontSize = 13.sp,
                                color = Color.White,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        }
                    } else if (queueMode == AppQueueMode.VERIFY) {
                        // ==========================================
                        // QUEUE 2: VERIFICATION QUEUE CONTROLS
                        // ==========================================
                        val current = currentUnverified
                        if (current != null) {
                            Text(
                                text = "🤖 AI Vision Annotation (${current.pendingCount} pending) • Verify or Edit points",
                                fontSize = 13.sp,
                                color = Color(0xFFCE93D8),
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Undo Button
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            api.undoLast(serverUrl)
                                            loadNextVerify()
                                        }
                                    },
                                    modifier = Modifier.height(48.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo", tint = Color(0xFFB0B0C0))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Undo", fontSize = 12.sp, color = Color(0xFFE0E0E0), fontWeight = FontWeight.Bold)
                                }
                                Button(
                                    onClick = {
                                        scope.launch {
                                            api.verifyAnnotation(
                                                baseUrl = serverUrl,
                                                batch = activeBatch,
                                                bubbleId = current.bubbleId,
                                                verified = false,
                                                crunchPoints = crunchPoints.toList(),
                                                dividingLines = dividingLines.toList(),
                                                notes = "rejected_during_verification"
                                            )
                                            loadNextVerify()
                                        }
                                    },
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Reject", modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Reject ➔ Recheck", fontWeight = FontWeight.Bold)
                                }

                                Button(
                                    onClick = {
                                        scope.launch {
                                            api.verifyAnnotation(
                                                baseUrl = serverUrl,
                                                batch = activeBatch,
                                                bubbleId = current.bubbleId,
                                                verified = true,
                                                crunchPoints = crunchPoints.toList(),
                                                dividingLines = dividingLines.toList()
                                            )
                                            loadNextVerify()
                                        }
                                    },
                                    modifier = Modifier.weight(1.3f).height(48.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = "Verify", modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Verify & Commit ✓", fontWeight = FontWeight.Bold)
                                }
                            }
                        } else {
                            Text(
                                text = "No unverified annotations in queue 🎉",
                                fontSize = 13.sp,
                                color = Color.White,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        }
                    } else if (isMinimalMode && queueMode == AppQueueMode.ANNOTATE) {
                        // ==========================================
                        // QUEUE 1: MINIMAL MODE (AUTO-SUGGEST DUAL LOOP)
                        // ==========================================
                        if (isAutoSuggestLoading) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(vertical = 10.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color(0xFF00E5FF))
                                Text("Running 0-token Concavity Detection...", color = Color(0xFF00E5FF), fontSize = 13.sp)
                            }
                        } else if (isAutoSuggestPreview) {
                            Text(
                                text = if (isEditingAutoSuggest) "✏️ Drag notches on image to adjust • Cutline moves dynamically" else "✨ Auto-Suggested Notches & Cutline. Accept, Edit, or Send to AI?",
                                fontSize = 13.sp,
                                color = if (isEditingAutoSuggest) Color(0xFFEEFF41) else Color(0xFF00E5FF),
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Undo Button
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            api.undoLast(serverUrl)
                                            loadNextAnnotate()
                                        }
                                    },
                                    modifier = Modifier.height(48.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo", tint = Color(0xFFB0B0C0))
                                }

                                // Reject -> Send to AI
                                Button(
                                    onClick = {
                                        val bubble = currentBubble ?: return@Button
                                        scope.launch {
                                            api.flagConjoined(serverUrl, activeBatch, bubble.bubbleId, "auto_suggest_rejected")
                                            loadNextAnnotate()
                                        }
                                    },
                                    modifier = Modifier.weight(0.95f).height(48.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC2185B)),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Reject 🤖", fontWeight = FontWeight.Bold, fontSize = 11.5.sp)
                                }

                                // Edit or Reset
                                if (!isEditingAutoSuggest) {
                                    OutlinedButton(
                                        onClick = {
                                            isEditingAutoSuggest = true
                                        },
                                        modifier = Modifier.weight(0.9f).height(48.dp),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("Edit ✏️", fontSize = 12.sp)
                                    }
                                } else {
                                    val currentCutCount = crunchPoints.size / 2
                                    if (currentCutCount > 1) {
                                        OutlinedButton(
                                            onClick = { removeLastCut() },
                                            modifier = Modifier.weight(0.70f).height(48.dp),
                                            shape = RoundedCornerShape(8.dp),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF8A80)),
                                            contentPadding = PaddingValues(horizontal = 4.dp)
                                        ) {
                                            Text("- Cut", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }

                                    if (currentCutCount < 6) {
                                        OutlinedButton(
                                            onClick = { addCut() },
                                            modifier = Modifier.weight(0.90f).height(48.dp),
                                            shape = RoundedCornerShape(8.dp),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E5FF)),
                                            contentPadding = PaddingValues(horizontal = 4.dp)
                                        ) {
                                            Icon(Icons.Default.Add, contentDescription = "Add Cut", modifier = Modifier.size(14.dp))
                                            Spacer(Modifier.width(1.dp))
                                            Text("+ Cut ${currentCutCount + 1}", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            val bubble = currentBubble ?: return@OutlinedButton
                                            isAutoSuggestLoading = true
                                            scope.launch {
                                                val autoRes = api.autoSuggest(serverUrl, activeBatch, bubble.bubbleId)
                                                isAutoSuggestLoading = false
                                                autoRes.onSuccess { res ->
                                                    candidatePoints.clear()
                                                    candidatePoints.addAll(res.candidatePoints)
                                                    if (res.success && res.crunchPoints.isNotEmpty()) {
                                                        crunchPoints.clear()
                                                        crunchPoints.addAll(res.crunchPoints)
                                                        dividingLines.clear()
                                                        dividingLines.addAll(res.dividingLines)
                                                    }
                                                }
                                            }
                                        },
                                        modifier = Modifier.weight(0.75f).height(48.dp),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("Reset ↺", fontSize = 11.5.sp)
                                    }
                                }

                                // Accept / Confirm -> Verified Annotated
                                Button(
                                    onClick = {
                                        val bubble = currentBubble ?: return@Button
                                        scope.launch {
                                            api.submitAnnotation(
                                                baseUrl = serverUrl,
                                                batch = activeBatch,
                                                bubbleId = bubble.bubbleId,
                                                isConjoined = true,
                                                crunchPoints = crunchPoints.toList(),
                                                dividingLines = dividingLines.toList()
                                            )
                                            loadNextAnnotate()
                                        }
                                    },
                                    modifier = Modifier.weight(1.3f).height(48.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        if (isEditingAutoSuggest) "Confirm & Verify ✓" else "Accept & Verify ✓",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.5.sp
                                    )
                                }
                            }
                        } else if (autoSuggestFailed) {
                            Text(
                                text = "⚠️ No obvious notches auto-detected. Send to AI or mark manually?",
                                fontSize = 13.sp,
                                color = Color(0xFFFFCC80),
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        val bubble = currentBubble ?: return@Button
                                        scope.launch {
                                            api.flagConjoined(serverUrl, activeBatch, bubble.bubbleId, "no_obvious_notches")
                                            loadNextAnnotate()
                                        }
                                    },
                                    modifier = Modifier.weight(1.1f).height(48.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC2185B)),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Send to AI 🤖", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }

                                Button(
                                    onClick = {
                                        autoSuggestFailed = false
                                        isAutoSuggestPreview = true
                                        isEditingAutoSuggest = true
                                        phase = AnnotationPhase.CRUNCH_POINTS
                                    },
                                    modifier = Modifier.weight(1.1f).height(48.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0097A7)),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Mark Manually ✏️", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }

                                OutlinedButton(
                                    onClick = {
                                        autoSuggestFailed = false
                                    },
                                    modifier = Modifier.weight(0.7f).height(48.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Back", fontSize = 12.sp)
                                }
                            }
                        } else {
                            // Step 1: Conjoined Decision
                            Text(
                                text = "Is this speech bubble conjoined?",
                                fontSize = 13.sp,
                                color = Color(0xFFE0E0E0),
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Undo Button (Bottom Thumb-Friendly)
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            api.undoLast(serverUrl)
                                            loadNextAnnotate()
                                        }
                                    },
                                    modifier = Modifier.height(48.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo", tint = Color(0xFFB0B0C0))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Undo", fontSize = 12.sp, color = Color(0xFFE0E0E0), fontWeight = FontWeight.Bold)
                                }

                                Button(
                                    onClick = {
                                        val bubble = currentBubble ?: return@Button
                                        scope.launch {
                                            api.submitAnnotation(serverUrl, activeBatch, bubble.bubbleId, isConjoined = false, emptyList(), emptyList())
                                            loadNextAnnotate()
                                        }
                                    },
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Single (No)", fontWeight = FontWeight.Bold)
                                }

                                Button(
                                    onClick = {
                                        val bubble = currentBubble ?: return@Button
                                        isAutoSuggestLoading = true
                                        scope.launch {
                                            val autoRes = api.autoSuggest(serverUrl, activeBatch, bubble.bubbleId)
                                            isAutoSuggestLoading = false
                                            autoRes.onSuccess { res ->
                                                candidatePoints.clear()
                                                candidatePoints.addAll(res.candidatePoints)
                                                if (res.success && res.crunchPoints.isNotEmpty()) {
                                                    crunchPoints.clear()
                                                    crunchPoints.addAll(res.crunchPoints)
                                                    dividingLines.clear()
                                                    dividingLines.addAll(res.dividingLines)
                                                    isAutoSuggestPreview = true
                                                    autoSuggestFailed = false
                                                } else {
                                                    autoSuggestFailed = true
                                                }
                                            }.onFailure {
                                                autoSuggestFailed = true
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1.1f).height(48.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0097A7)),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Conjoined (Yes ➔)", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    } else {
                        // ==========================================
                        // QUEUE 1: MANUAL MODE (FULL STEP 1 -> 2 -> 3)
                        // ==========================================
                        when (phase) {
                            AnnotationPhase.DECISION -> {
                                Text(
                                    text = "Step 1: Is this speech bubble conjoined?",
                                    fontSize = 13.sp,
                                    color = Color(0xFFE0E0E0),
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Undo Button (Bottom Thumb-Friendly)
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                api.undoLast(serverUrl)
                                                loadNextAnnotate()
                                            }
                                        },
                                        modifier = Modifier.height(48.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 14.dp)
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo", tint = Color(0xFFB0B0C0))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Undo", fontSize = 12.sp, color = Color(0xFFE0E0E0), fontWeight = FontWeight.Bold)
                                    }

                                    Button(
                                        onClick = {
                                            val bubble = currentBubble ?: return@Button
                                            scope.launch {
                                                api.submitAnnotation(serverUrl, activeBatch, bubble.bubbleId, isConjoined = false, emptyList(), emptyList())
                                                loadNextAnnotate()
                                            }
                                        },
                                        modifier = Modifier.weight(1f).height(48.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("Single (No)", fontWeight = FontWeight.Bold)
                                    }

                                    Button(
                                        onClick = {
                                            val bubble = currentBubble ?: return@Button
                                            isAutoSuggestLoading = true
                                            scope.launch {
                                                val autoRes = api.autoSuggest(serverUrl, activeBatch, bubble.bubbleId)
                                                isAutoSuggestLoading = false
                                                autoRes.onSuccess { res ->
                                                    if (res.success && res.crunchPoints.isNotEmpty()) {
                                                        crunchPoints.clear()
                                                        crunchPoints.addAll(res.crunchPoints)
                                                        dividingLines.clear()
                                                        dividingLines.addAll(res.dividingLines)
                                                    }
                                                }
                                                phase = AnnotationPhase.CRUNCH_POINTS
                                            }
                                        },
                                        modifier = Modifier.weight(1.1f).height(48.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0097A7)),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("Conjoined (Yes ➔)", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            AnnotationPhase.CRUNCH_POINTS -> {
                                val ptsCount = crunchPoints.size
                                val guideText = if (ptsCount == 0) {
                                    "✏️ Drag small box around Notch A (Cyan)"
                                } else if (ptsCount == 1) {
                                    "✏️ Drag small box around Notch B (Amber)"
                                } else {
                                    "✓ $ptsCount notches marked. Drag any box to adjust position."
                                }

                                Text(
                                    text = guideText,
                                    fontSize = 13.sp,
                                    color = if (ptsCount >= 2) Color(0xFF00E5FF) else Color(0xFFFFD54F),
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (selectedPointIndex != null && selectedPointIndex in crunchPoints.indices) {
                                        Button(
                                            onClick = {
                                                selectedPointIndex?.let { idx ->
                                                    if (idx in crunchPoints.indices) crunchPoints.removeAt(idx)
                                                }
                                                selectedPointIndex = null
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                                            modifier = Modifier.weight(0.9f).height(48.dp),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Delete Notch")
                                        }
                                    } else {
                                        OutlinedButton(
                                            onClick = {
                                                if (crunchPoints.isNotEmpty()) crunchPoints.removeAt(crunchPoints.lastIndex)
                                                else phase = AnnotationPhase.DECISION
                                            },
                                            modifier = Modifier.weight(0.9f).height(48.dp),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text(if (crunchPoints.isNotEmpty()) "Undo Notch" else "Back")
                                        }
                                    }

                                    Button(
                                        onClick = {
                                            selectedPointIndex = null
                                            if (crunchPoints.size >= 2 && dividingLines.isEmpty()) {
                                                val pA = crunchPoints[0].center
                                                val pB = crunchPoints[1].center
                                                dividingLines.add(DividingLineAnnotation(1, listOf(pA, pB)))
                                            }
                                            phase = AnnotationPhase.DIVIDING_LINE
                                        },
                                        enabled = crunchPoints.isNotEmpty(),
                                        modifier = Modifier.weight(1.1f).height(48.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0288D1)),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("Cut Line ➔", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            AnnotationPhase.DIVIDING_LINE -> {
                                val hasLine = dividingLines.isNotEmpty()
                                Text(
                                    text = if (hasLine) "✓ Straight cut line ready! Drag to redraw, or Submit." else "📐 Drag from Notch A to Notch B to draw cut line",
                                    fontSize = 13.sp,
                                    color = if (hasLine) Color(0xFFEEFF41) else Color(0xFFE0E0E0),
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            if (dividingLines.isNotEmpty()) dividingLines.clear()
                                            else phase = AnnotationPhase.CRUNCH_POINTS
                                        },
                                        modifier = Modifier.weight(0.8f).height(48.dp),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(if (dividingLines.isNotEmpty()) "Redraw" else "Back")
                                    }

                                    Button(
                                        onClick = {
                                            val bubble = currentBubble ?: return@Button
                                            scope.launch {
                                                api.submitAnnotation(
                                                    baseUrl = serverUrl,
                                                    batch = activeBatch,
                                                    bubbleId = bubble.bubbleId,
                                                    isConjoined = true,
                                                    crunchPoints = crunchPoints.toList(),
                                                    dividingLines = dividingLines.toList()
                                                )
                                                loadNextAnnotate()
                                            }
                                        },
                                        enabled = hasLine,
                                        modifier = Modifier.weight(1.2f).height(48.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C853)),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("Submit & Next ➔", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { paddingVals ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingVals)
        ) {
            if (queueMode == AppQueueMode.SINGLES && singlesViewMode == "grid") {
                if (isLoading && singlesList.isEmpty()) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = Color(0xFF00E5FF)
                    )
                } else if (singlesList.isEmpty()) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "No singles found in $activeBatch",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { loadSinglesList() }) {
                            Text("Reload Singles")
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(singlesGridColumns),
                        contentPadding = PaddingValues(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(singlesList, key = { _, item -> item.bubbleId }) { idx, item ->
                            AsyncThumbnailCard(
                                item = item,
                                index = idx,
                                columns = singlesGridColumns,
                                api = api,
                                onClick = {
                                    singlesCurrentIndex = idx
                                    singlesViewMode = "single"
                                    loadSinglesItem(item.bubbleId)
                                },
                                onQuickConjoined = {
                                    quickMarkConjoined(idx)
                                }
                            )
                        }
                    }
                }
            } else if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color(0xFF00E5FF)
                )
            } else if (currentBitmap != null) {
                AnnotationCanvas(
                    bitmap = currentBitmap,
                    phase = if (isAutoSuggestPreview || queueMode == AppQueueMode.VERIFY || queueMode == AppQueueMode.REVIEW || queueMode == AppQueueMode.SINGLES) AnnotationPhase.CRUNCH_POINTS else phase,
                    crunchPoints = crunchPoints,
                    selectedPointIndex = selectedPointIndex,
                    onSelectPoint = { selectedPointIndex = it },
                    onAddCrunchPoint = { pt ->
                        crunchPoints.add(pt)
                        if (crunchPoints.size == 2 && dividingLines.isEmpty()) {
                            dividingLines.add(
                                DividingLineAnnotation(
                                    lineId = 1,
                                    points = listOf(crunchPoints[0].center, crunchPoints[1].center)
                                )
                            )
                        }
                    },
                    onUpdateCrunchPoint = { idx, pt ->
                        if (idx in crunchPoints.indices) {
                            crunchPoints[idx] = pt
                            val cutIndex = idx / 2
                            val isFirstOfPair = (idx % 2 == 0)
                            if (cutIndex < dividingLines.size) {
                                val line = dividingLines[cutIndex]
                                val otherIdx = if (isFirstOfPair) idx + 1 else idx - 1
                                if (otherIdx in crunchPoints.indices) {
                                    val otherCenter = crunchPoints[otherIdx].center
                                    val newPoints = if (isFirstOfPair) listOf(pt.center, otherCenter) else listOf(otherCenter, pt.center)
                                    dividingLines[cutIndex] = line.copy(points = newPoints)
                                }
                            } else if (crunchPoints.size >= (cutIndex + 1) * 2) {
                                val otherIdx = if (isFirstOfPair) idx + 1 else idx - 1
                                val otherCenter = crunchPoints[otherIdx].center
                                val newPoints = if (isFirstOfPair) listOf(pt.center, otherCenter) else listOf(otherCenter, pt.center)
                                dividingLines.add(DividingLineAnnotation(lineId = cutIndex + 1, points = newPoints))
                            }
                        }
                    },
                    onDeletePoint = { idx ->
                        if (idx in crunchPoints.indices) crunchPoints.removeAt(idx)
                    },
                    dividingLines = dividingLines,
                    onSetDividingLine = {
                        dividingLines.clear()
                        dividingLines.add(it)
                    },
                    candidatePoints = candidatePoints,
                    onCandidatePointTapped = { cand ->
                        val targetIdx = selectedPointIndex ?: (if (crunchPoints.isNotEmpty()) 0 else null)
                        if (targetIdx != null && targetIdx in crunchPoints.indices) {
                            val cp = crunchPoints[targetIdx]
                            val w = cp.rect.width()
                            val h = cp.rect.height()
                            val newRect = Rect(cand.x - w / 2, cand.y - h / 2, cand.x + w / 2, cand.y + h / 2)
                            val updatedCp = cp.copy(rect = newRect, center = cand)
                            crunchPoints[targetIdx] = updatedCp

                            val cutIndex = targetIdx / 2
                            val isFirstOfPair = (targetIdx % 2 == 0)
                            if (cutIndex < dividingLines.size) {
                                val otherIdx = if (isFirstOfPair) targetIdx + 1 else targetIdx - 1
                                if (otherIdx in crunchPoints.indices) {
                                    val otherCenter = crunchPoints[otherIdx].center
                                    val newPoints = if (isFirstOfPair) listOf(cand, otherCenter) else listOf(otherCenter, cand)
                                    dividingLines[cutIndex] = dividingLines[cutIndex].copy(points = newPoints)
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Info Badge at top center
                val badgeText = if (queueMode == AppQueueMode.SINGLES) {
                    if (singlesList.isNotEmpty() && singlesCurrentIndex in singlesList.indices) {
                        val s = singlesList[singlesCurrentIndex]
                        val isConj = currentVerified?.isConjoined ?: s.isConjoined
                        "🔍 Singles #${singlesCurrentIndex + 1}/${singlesList.size} [${s.bubbleId.substringAfterLast('_')}] ${if (isConj) "Conjoined" else "Single"}"
                    } else ""
                } else if (queueMode == AppQueueMode.REVIEW) {
                    currentVerified?.let { v -> "🔍 #${reviewCurrentIndex + 1}/${reviewList.size} [${v.bubbleId}] ${if (v.isConjoined) "Conjoined (${dividingLines.size} cut${if (dividingLines.size > 1) "s" else ""})" else "Single"}" } ?: ""
                } else if (queueMode == AppQueueMode.VERIFY) {
                    currentUnverified?.let { u -> "🤖 AI Candidate [${u.bubbleId}]" } ?: ""
                } else {
                    currentBubble?.let { b -> "${b.bubbleId}  [${b.width}x${b.height}]  ${(b.confidence * 100).toInt()}%" } ?: ""
                }

                if (badgeText.isNotEmpty()) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 10.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xCC1A1A24)
                    ) {
                        Text(
                            text = badgeText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFE0E0E0),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (queueMode == AppQueueMode.VERIFY) "No pending AI annotations to verify! 🎉"
                               else if (queueMode == AppQueueMode.SINGLES) "No singles in queue"
                               else "All bubbles completed! 🎉",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = {
                        when (queueMode) {
                            AppQueueMode.VERIFY -> loadNextVerify()
                            AppQueueMode.REVIEW -> loadReviewList()
                            AppQueueMode.SINGLES -> loadSinglesList()
                            else -> loadNextAnnotate()
                        }
                    }) {
                        Text("Refresh Queue")
                    }
                }
            }
        }
    }

    // SETTINGS DIALOG
    if (showSettingsDialog) {
        var tempIp by remember { mutableStateOf(serverIp) }
        var tempPort by remember { mutableStateOf(serverPort) }
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("Server Connection") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = tempIp,
                        onValueChange = { tempIp = it },
                        label = { Text("PC IP Address") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = tempPort,
                        onValueChange = { tempPort = it },
                        label = { Text("Port") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        serverIp = tempIp
                        serverPort = tempPort
                        showSettingsDialog = false
                        if (queueMode == AppQueueMode.ANNOTATE) loadNextAnnotate() else loadNextVerify()
                    }
                ) {
                    Text("Connect")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // DATASET PROGRESS MINI WINDOW DIALOG
    if (showProgressDialog) {
        val s = serverStatus
        AlertDialog(
            onDismissRequest = { showProgressDialog = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Analytics, contentDescription = null, tint = Color(0xFF00E5FF))
                    Text("Dataset Progress", fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            text = {
                if (s != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // Current Batch Card
                        Surface(
                            color = Color(0xFF1E1E28),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color(0xFF2A2A3C)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Current Batch (${s.currentBatch})",
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF00E5FF),
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = "${s.percentComplete.toInt()}%",
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFF00E676),
                                        fontSize = 14.sp
                                    )
                                }

                                val batchProgress = if (s.total > 0) (s.batchDone.toFloat() / s.total).coerceIn(0f, 1f) else 0f
                                LinearProgressIndicator(
                                    progress = { batchProgress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                    color = Color(0xFF00E5FF),
                                    trackColor = Color(0xFF2E2E3E)
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text("Done in Batch", fontSize = 11.sp, color = Color(0xFF9E9E9E))
                                        Text("${s.batchDone} / ${s.total}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("Left in Batch", fontSize = 11.sp, color = Color(0xFF9E9E9E))
                                        Text("${s.batchLeft}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFB74D))
                                    }
                                }

                                HorizontalDivider(color = Color(0xFF2A2A3C), thickness = 0.8.dp)

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceAround
                                ) {
                                    Text("Single: ${s.single}", fontSize = 11.sp, color = Color(0xFF81C784), fontWeight = FontWeight.SemiBold)
                                    Text("Conjoined: ${s.conjoined}", fontSize = 11.sp, color = Color(0xFF4FC3F7), fontWeight = FontWeight.SemiBold)
                                    Text("Discard: ${s.discarded}", fontSize = 11.sp, color = Color(0xFFE57373), fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }

                        // Total Overall Card
                        Surface(
                            color = Color(0xFF1E1E28),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color(0xFF2A2A3C)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Total Across All Batches",
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFCE93D8),
                                        fontSize = 14.sp
                                    )
                                    val totalPct = if (s.totalAll > 0) (s.doneAll.toFloat() / s.totalAll * 100).toInt() else 0
                                    Text(
                                        text = "$totalPct%",
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFFCE93D8),
                                        fontSize = 14.sp
                                    )
                                }

                                val overallProgress = if (s.totalAll > 0) (s.doneAll.toFloat() / s.totalAll).coerceIn(0f, 1f) else 0f
                                LinearProgressIndicator(
                                    progress = { overallProgress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                    color = Color(0xFFAB47BC),
                                    trackColor = Color(0xFF2E2E3E)
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text("Total Done", fontSize = 11.sp, color = Color(0xFF9E9E9E))
                                        Text("${s.doneAll} / ${s.totalAll}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("Total Left to Do", fontSize = 11.sp, color = Color(0xFF9E9E9E))
                                        Text("${s.leftAll}", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFFFF7043))
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Text("No server connection status available", color = Color(0xFFFF5252))
                }
            },
            confirmButton = {
                Button(
                    onClick = { showProgressDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0097A7))
                ) {
                    Text("Close", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = Color(0xFF14141E)
        )
    }
}

@Composable
fun AsyncThumbnailCard(
    item: BubbleThumbItem,
    index: Int,
    columns: Int,
    api: AnnotatorApiClient,
    onClick: () -> Unit,
    onQuickConjoined: () -> Unit,
    modifier: Modifier = Modifier
) {
    var bitmap by remember(item.imageUrl) { mutableStateOf(ThumbnailCache.lru.get(item.imageUrl)) }

    LaunchedEffect(item.imageUrl) {
        if (bitmap == null) {
            val res = api.fetchThumbnail(item.imageUrl, maxDim = 300)
            res.onSuccess { bitmap = it }
        }
    }

    val isConj = item.isConjoined
    val imgHeight = when (columns) {
        2 -> 180.dp
        4 -> 105.dp
        else -> 135.dp
    }

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        color = Color(0xFF1E1E28),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(
            width = if (isConj) 2.dp else 1.dp,
            color = if (isConj) Color(0xFF00E676) else Color(0xFF2E2E3E)
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Header bar on card
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (isConj) Color(0xFF1B5E20) else Color(0xFF262634))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "#${index + 1} ${item.bubbleId.substringAfterLast('_')}",
                    fontSize = if (columns == 4) 9.sp else 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                if (isConj) {
                    Text(
                        text = "CONJ ✓",
                        fontSize = if (columns == 4) 8.sp else 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFFB9F6CA)
                    )
                }
            }

            // Image Thumbnail
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(imgHeight)
                    .background(Color(0xFF121218)),
                contentAlignment = Alignment.Center
            ) {
                val bmp = bitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = item.bubbleId,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(2.dp),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFF00E5FF).copy(alpha = 0.5f)
                    )
                }
            }

            // Bottom action row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF20202A))
                    .padding(horizontal = 4.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Inspect",
                    fontSize = if (columns == 4) 8.5.sp else 10.sp,
                    color = Color(0xFF80D8FF)
                )

                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(onClick = onQuickConjoined),
                    color = if (isConj) Color(0xFF2E7D32) else Color(0xFF37474F),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = if (isConj) "Conj ✓" else "➔ Conj",
                        fontSize = if (columns == 4) 8.5.sp else 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

