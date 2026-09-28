package com.raen.kisaratranslator.data.model

enum class ViewMode(val label: String, val subtitle: String) {
    DETECTION_1("1. Det 1", "Pass 1 Global Speech & Text Detection"),
    DETECTION_2("2. Det 2", "Pass 2 Focused Boost Detection (Faint Glyphs)"),
    LINE_STRIPS("3. Lines", "Bridged Vertical Line Strips"),
    GROUPING("4a. Group", "Grouped Dialogue Lobes & Text Units"),
    CRUNCH_1("4b. Crunch 1", "Clean Polygon Mask & Waist Notches"),
    CRUNCH_2("4c. Crunch 2", "Seam Line & Obstacle Deflections"),
    CRUNCH_3("4d. Crunch 3", "Separated Lobes & Text Allocation"),
    OCR_CROPS("5. OCR", "Multi-Line Sweet-Spot OCR & Chunks"),
    ORDER_FLOW("6. Bubbles", "Grouped Bubbles & Reading Order Flow"),
    INPAINTED("7. Inpaint", "Cleaned Background Canvas"),
    TRANSLATED("8. Final", "Rendered Typography & Translation"),
}
