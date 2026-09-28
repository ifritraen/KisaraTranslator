package com.raen.method3.annotator.data

import android.graphics.Point
import android.graphics.Rect

data class BubbleItem(
    val bubbleId: String,
    val filename: String,
    val imageUrl: String,
    val width: Int,
    val height: Int,
    val pageName: String,
    val confidence: Float,
    val index: Int,
    val total: Int,
)

data class CrunchPointAnnotation(
    val id: Int,
    val label: String,
    val rect: Rect,
    val center: Point,
)

data class DividingLineAnnotation(
    val lineId: Int,
    val points: List<Point>,
)

data class AutoSuggestResult(
    val success: Boolean,
    val crunchPoints: List<CrunchPointAnnotation>,
    val dividingLines: List<DividingLineAnnotation>,
    val candidatePoints: List<Point> = emptyList(),
)

data class VerifiedBubbleItem(
    val bubbleId: String,
    val filename: String,
    val imageUrl: String,
    val isConjoined: Boolean,
    val crunchPoints: List<CrunchPointAnnotation>,
    val dividingLines: List<DividingLineAnnotation>,
    val index: Int,
    val total: Int,
)

data class UnverifiedBubbleItem(
    val bubbleId: String,
    val batch: String,
    val filename: String,
    val imageUrl: String,
    val crunchPoints: List<CrunchPointAnnotation>,
    val dividingLines: List<DividingLineAnnotation>,
    val pendingCount: Int,
)

data class ServerStatus(
    val status: String,
    val localIp: String,
    val batches: List<String>,
    val currentBatch: String,
    val total: Int,
    val unlabeled: Int = 0,
    val annotated: Int,
    val verified: Int = 0,
    val conjoinedQueued: Int = 0,
    val unverified: Int = 0,
    val recheck: Int = 0,
    val conjoined: Int,
    val single: Int,
    val discarded: Int,
    val percentComplete: Float,
    val batchDone: Int = 0,
    val batchLeft: Int = 0,
    val totalAll: Int = 0,
    val doneAll: Int = 0,
    val leftAll: Int = 0,
)

enum class AnnotationPhase {
    DECISION,       // Step 1: Is it conjoined?
    CRUNCH_POINTS,  // Step 2: Draw bounding boxes for crunch points A & B
    DIVIDING_LINE   // Step 3: Draw dividing line separating the lobes
}

enum class AppQueueMode {
    ANNOTATE,       // Main queue (non_annotated/)
    REVIEW,         // Review verified bubbles (134 conjoined / all 1662)
    VERIFY,         // Review AI-annotated candidates (unverified_annotated/)
    SINGLES         // Recheck verified singles in Grid or Single-item mode
}

data class BubbleThumbItem(
    val bubbleId: String,
    val filename: String,
    val imageUrl: String,
    val isConjoined: Boolean,
    val manifestIndex: Int,
)
