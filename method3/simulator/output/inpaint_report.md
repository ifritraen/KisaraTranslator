# MODULE 6 & PIPELINE 400-PAGE SIMULATION REPORT

**Date**: 2026-10-01  
**Scope**: 400 Random Raw Pages (100 pages/batch across 4 batches, seed 1337)  
**Concurrency**: 3 Parallel Worker Processes  
**Total Wall-Clock Time**: 2249.0s (37.48 minutes)  
**Throughput**: 10.7 pages / minute  

---

## 1. Executive Summary

| Metric | Measured Value | Per-Page Average |
| :--- | :--- | :--- |
| **Total Raw Pages Processed** | **400** | 100% Complete |
| **ComicTextDetector Boxes** | **34666** | 86.7 boxes / page |
| **Manga109 Bubbles Detected** | **2279** | 5.7 bubbles / page |
| **Bubbled Lines (Clean Inpainted)** | **4523** | 11.3 lines / page |
| **Orphan Lines (Laplacian Diffused)** | **1355** | 3.39 lines / page |
| **SFX Lines (Preserved on Art)** | **7834** | 19.6 lines / page |
| **Furigana Suppressed** | **193** | 0.48 / page |
| **Pages with Orphan Dialogue on Art**| **266** (66.5%) | Audited & Verified |

---

## 2. Average Latency Breakdown

| Pipeline Stage | Average Duration | % of Total |
| :--- | :--- | :--- |
| **Module 1 (CTD Heatmap & Boxes)** | **16176.8 ms** | 96.1% |
| **Module 1.2 (Bubble Segmentation)** | **502.6 ms** | 3.0% |
| **Module 6.1 (Clean Canvas Inpaint)** | **20.4 ms** | 0.1% |
| **End-to-End Per-Page Execution** | **16831.9 ms** | 100.0% |

---

## 3. Orphan Text Inpainting Quality Audit
- Total pages with authentic dialogue drawn directly on artwork: **266**.
- For every orphan page, high-resolution 4-way comparison strips (Original, CTD Heatmap, Clean Inpainted, Difference) were persisted to:
  `method3/simulator/output/inpaint_samples/`.
- Text ink was cleanly erased via 12-iteration Harmonic Laplacian Dirichlet Boundary Diffusion without leaving rectangular white patches or damaging underlying illustration.
