package com.raen.kisaratranslator.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raen.kisaratranslator.data.download.TranslationModelManager
import com.raen.kisaratranslator.data.model.TranslationModelType
import com.raen.kisaratranslator.ui.components.ModelDownloadCard

@Composable
fun ModelManagerScreen(
    modelManager: TranslationModelManager,
    modifier: Modifier = Modifier,
) {
    var updateTrigger by remember { mutableIntStateOf(0) }

    val totalBytes = remember(updateTrigger) {
        TranslationModelType.entries.sumOf { type ->
            val file = modelManager.getModelFile(type)
            if (file.exists()) file.length() else 0L
        }
    }
    val totalMb = totalBytes / (1024 * 1024)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        // Storage Header Card
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF1E1E1E),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Storage,
                        contentDescription = null,
                        tint = Color(0xFF00E676),
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("Model Storage", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
                        Text("$totalMb MB used on device", fontSize = 12.sp, color = Color.Gray)
                    }
                }

                Button(
                    onClick = {
                        // Download full MangaOCR suite in one tap
                        modelManager.startDownload(TranslationModelType.MANGA_OCR_ENCODER) { updateTrigger++ }
                        modelManager.startDownload(TranslationModelType.MANGA_OCR_DECODER) { updateTrigger++ }
                        modelManager.startDownload(TranslationModelType.MANGA_OCR_TOKENIZER) { updateTrigger++ }
                    },
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Icon(Icons.Outlined.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Get MangaOCR", fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Group 1: Speech Balloon & Text Detectors
        Text(
            text = "1. Speech Balloon & Text Detectors",
            fontWeight = FontWeight.Bold,
            color = Color(0xFF00B0FF),
            fontSize = 14.sp,
        )
        Spacer(modifier = Modifier.height(8.dp))

        ModelDownloadCard(
            type = TranslationModelType.COMIC_TEXT_DETECTOR,
            modelManager = modelManager,
            onModelUpdated = { updateTrigger++ },
        )
        Spacer(modifier = Modifier.height(10.dp))

        ModelDownloadCard(
            type = TranslationModelType.BUBBLE_DETECTOR,
            modelManager = modelManager,
            onModelUpdated = { updateTrigger++ },
        )
        Spacer(modifier = Modifier.height(10.dp))

        ModelDownloadCard(
            type = TranslationModelType.MANGA_DETECTOR_2024,
            modelManager = modelManager,
            onModelUpdated = { updateTrigger++ },
        )
        Spacer(modifier = Modifier.height(10.dp))

        ModelDownloadCard(
            type = TranslationModelType.PADDLE_OCR_DET,
            modelManager = modelManager,
            onModelUpdated = { updateTrigger++ },
        )
        Spacer(modifier = Modifier.height(10.dp))

        ModelDownloadCard(
            type = TranslationModelType.MANGA109_BUBBLE_SEG,
            modelManager = modelManager,
            onModelUpdated = { updateTrigger++ },
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Group 2: MangaOCR Japanese Suite
        Text(
            text = "2. MangaOCR Suite (Specialized Japanese ViT)",
            fontWeight = FontWeight.Bold,
            color = Color(0xFF00E676),
            fontSize = 14.sp,
        )
        Spacer(modifier = Modifier.height(8.dp))

        ModelDownloadCard(
            type = TranslationModelType.MANGA_OCR_ENCODER,
            modelManager = modelManager,
            onModelUpdated = { updateTrigger++ },
        )
        Spacer(modifier = Modifier.height(10.dp))

        ModelDownloadCard(
            type = TranslationModelType.MANGA_OCR_DECODER_QUANTIZED,
            modelManager = modelManager,
            onModelUpdated = { updateTrigger++ },
        )
        Spacer(modifier = Modifier.height(10.dp))

        ModelDownloadCard(
            type = TranslationModelType.MANGA_OCR_DECODER,
            modelManager = modelManager,
            onModelUpdated = { updateTrigger++ },
        )
        Spacer(modifier = Modifier.height(10.dp))

        ModelDownloadCard(
            type = TranslationModelType.MANGA_OCR_TOKENIZER,
            modelManager = modelManager,
            onModelUpdated = { updateTrigger++ },
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Group 3: Manga Translator 48px CTC Suite
        Text(
            text = "3. Manga Translator 48px CTC Suite (Ultra-Fast)",
            fontWeight = FontWeight.Bold,
            color = Color(0xFF76FF03),
            fontSize = 14.sp,
        )
        Spacer(modifier = Modifier.height(8.dp))

        ModelDownloadCard(
            type = TranslationModelType.OCR_48PX_CTC,
            modelManager = modelManager,
            onModelUpdated = { updateTrigger++ },
        )
        Spacer(modifier = Modifier.height(10.dp))

        ModelDownloadCard(
            type = TranslationModelType.OCR_48PX_ALPHABET,
            modelManager = modelManager,
            onModelUpdated = { updateTrigger++ },
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Group 4: PaddleOCR Suite
        Text(
            text = "4. PaddleOCR v5 Suite (Fast Multi-Language)",
            fontWeight = FontWeight.Bold,
            color = Color(0xFFFFAB00),
            fontSize = 14.sp,
        )
        Spacer(modifier = Modifier.height(8.dp))

        ModelDownloadCard(
            type = TranslationModelType.PADDLE_OCR_REC,
            modelManager = modelManager,
            onModelUpdated = { updateTrigger++ },
        )
        Spacer(modifier = Modifier.height(10.dp))

        ModelDownloadCard(
            type = TranslationModelType.PADDLE_OCR_DICT,
            modelManager = modelManager,
            onModelUpdated = { updateTrigger++ },
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Group 5: On-Device Neural Translation
        Text(
            text = "5. On-Device Translation Suite (Manga & Offline)",
            fontWeight = FontWeight.Bold,
            color = Color(0xFFFF4081),
            fontSize = 14.sp,
        )
        Spacer(modifier = Modifier.height(8.dp))

        ModelDownloadCard(
            type = TranslationModelType.OPUS_MT_ENCODER,
            modelManager = modelManager,
            onModelUpdated = { updateTrigger++ },
        )
        Spacer(modifier = Modifier.height(10.dp))

        ModelDownloadCard(
            type = TranslationModelType.OPUS_MT_DECODER,
            modelManager = modelManager,
            onModelUpdated = { updateTrigger++ },
        )
        Spacer(modifier = Modifier.height(10.dp))

        ModelDownloadCard(
            type = TranslationModelType.OPUS_MT_TOKENIZER,
            modelManager = modelManager,
            onModelUpdated = { updateTrigger++ },
        )
        Spacer(modifier = Modifier.height(10.dp))

        ModelDownloadCard(
            type = TranslationModelType.SUGOI_TRANSLATOR,
            modelManager = modelManager,
            onModelUpdated = { updateTrigger++ },
        )
        Spacer(modifier = Modifier.height(10.dp))

        ModelDownloadCard(
            type = TranslationModelType.QWEN_0_5B_INSTRUCT,
            modelManager = modelManager,
            onModelUpdated = { updateTrigger++ },
        )
        Spacer(modifier = Modifier.height(10.dp))

        ModelDownloadCard(
            type = TranslationModelType.QWEN_TOKENIZER,
            modelManager = modelManager,
            onModelUpdated = { updateTrigger++ },
        )

        Spacer(modifier = Modifier.height(32.dp))
    }
}
