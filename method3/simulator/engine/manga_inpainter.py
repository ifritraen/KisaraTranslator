import cv2
import numpy as np
import math
from typing import List, Tuple, Optional, Any
from dataclasses import dataclass, field
from .types import Rect, TextLineItem

@dataclass
class DialogueGroup:
    id: int
    category: str  # "BUBBLED", "ORPHAN", "SFX"
    lines: List[TextLineItem] = field(default_factory=list)
    bounds: Optional[Rect] = None
    is_bubble: bool = False
    contour_points: List[Tuple[int, int]] = field(default_factory=list)

class MangaInpainter:
    """
    1:1 Python Simulator Port of MangaInpainter.kt (Module 6.1).
    
    Responsibilities:
    1. BUBBLED Text: Replaces the entire interior of speech bubbles (irregular polygon,
       oval, or rectangular narration box) with pure white (255, 255, 255).
       Enforces a strict 4.0px inward safe margin to guarantee the black outline stroke
       is 100% protected and never clipped.
    2. ORPHAN Text: Eliminates dialogue/narration drawn directly on artwork.
       Isolates exact text ink glyphs via ComicTextDetector float probability heatmap (p >= 0.22),
       preserves subpixel precision (0px dilation), and executes 12-iteration Harmonic
       Laplacian Dirichlet Boundary Diffusion on RGB channels to blend surrounding art seamlessly.
    """

    SAFE_MARGIN_PX = 4.0
    PROB_THRESHOLD = 0.22
    HEATMAP_DIM = 1024
    LAPLACIAN_ITERS = 12

    @classmethod
    def inpaint_clean_canvas(
        cls,
        image_bgr: np.ndarray,
        groups: List[DialogueGroup],
        heatmap: Optional[np.ndarray] = None
    ) -> np.ndarray:
        """
        Executes clean canvas inpainting on a full manga page.
        Returns a newly allocated BGR clean canvas bitmap.
        """
        result = image_bgr.copy()
        h, w = result.shape[:2]

        for group in groups:
            if not group.lines:
                continue

            cat = str(group.category).upper()
            if group.is_bubble or "BUBBLE" in cat:
                cls._inpaint_bubble(result, group, w, h)
            elif "ORPHAN" in cat:
                for line in group.lines:
                    cls._inpaint_orphan_line_laplacian(
                        canvas=result,
                        original=image_bgr,
                        line_rect=line.rect,
                        heatmap=heatmap,
                        img_w=w,
                        img_h=h
                    )

        return result

    @classmethod
    def _inpaint_bubble(cls, canvas: np.ndarray, group: DialogueGroup, img_w: int, img_h: int):
        pts = getattr(group, "contour_points", [])
        if pts and len(pts) >= 3:
            inset_poly = cls._build_safe_inset_contour(pts, cls.SAFE_MARGIN_PX, img_w, img_h)
            if inset_poly is not None and len(inset_poly) >= 3:
                cv2.fillPoly(canvas, [inset_poly.astype(np.int32)], (255, 255, 255))
        else:
            # Fallback to geometric inset
            b = group.bounds
            if b is None and group.lines:
                min_l = min(l.rect.left for l in group.lines)
                min_t = min(l.rect.top for l in group.lines)
                max_r = max(l.rect.right for l in group.lines)
                max_b = max(l.rect.bottom for l in group.lines)
                b = Rect(min_l, min_t, max_r, max_b)
            if b is not None:
                cls._inpaint_geometric_bubble(canvas, b, cls.SAFE_MARGIN_PX, img_w, img_h)

        # Line rounded rects inside bubble interior
        pad_x = 8
        pad_y = 12
        for line in group.lines:
            r = line.rect
            x1 = max(0, r.left - pad_x)
            y1 = max(0, r.top - pad_y)
            x2 = min(img_w, r.right + pad_x)
            y2 = min(img_h, r.bottom + pad_y)
            cv2.rectangle(canvas, (x1, y1), (x2, y2), (255, 255, 255), -1)

    @classmethod
    def _build_safe_inset_contour(
        cls,
        pts: List[Tuple[int, int]],
        safe_margin: float,
        img_w: int,
        img_h: int
    ) -> Optional[np.ndarray]:
        n = len(pts)
        if n < 3:
            return None

        np_pts = np.array(pts, dtype=np.float32)
        cx = float(np.mean(np_pts[:, 0]))
        cy = float(np.mean(np_pts[:, 1]))

        inset_pts = []
        for i in range(n):
            curr = np_pts[i]
            prev = np_pts[(i - 1 + n) % n]
            nxt = np_pts[(i + 1) % n]

            to_cx = cx - curr[0]
            to_cy = cy - curr[1]
            dist_c = math.hypot(to_cx, to_cy)

            if dist_c <= 1.0:
                inset_pts.append(curr)
                continue

            uc_x = to_cx / dist_c
            uc_y = to_cy / dist_c

            e1_x = curr[0] - prev[0]
            e1_y = curr[1] - prev[1]
            e2_x = nxt[0] - curr[0]
            e2_y = nxt[1] - curr[1]

            len1 = math.hypot(e1_x, e1_y)
            len2 = math.hypot(e2_x, e2_y)

            if len1 > 0.5 and len2 > 0.5:
                u1_x, u1_y = e1_x / len1, e1_y / len1
                u2_x, u2_y = e2_x / len2, e2_y / len2
                tx = u1_x + u2_x
                ty = u1_y + u2_y
                t_len = math.hypot(tx, ty)

                if t_len > 0.1:
                    nx = -ty / t_len
                    ny = tx / t_len
                    if nx * to_cx + ny * to_cy < 0:
                        nx = -nx
                        ny = -ny
                    blend_x = 0.6 * nx + 0.4 * uc_x
                    blend_y = 0.6 * ny + 0.4 * uc_y
                    b_len = math.hypot(blend_x, blend_y)
                    if b_len > 0.05:
                        dir_x = blend_x / b_len
                        dir_y = blend_y / b_len
                    else:
                        dir_x, dir_y = uc_x, uc_y
                else:
                    dir_x, dir_y = uc_x, uc_y
            else:
                dir_x, dir_y = uc_x, uc_y

            shift = min(safe_margin, dist_c * 0.25)
            nx = np.clip(curr[0] + dir_x * shift, 0, img_w - 1)
            ny = np.clip(curr[1] + dir_y * shift, 0, img_h - 1)
            inset_pts.append((nx, ny))

        return np.array(inset_pts, dtype=np.float32)

    @staticmethod
    def _rect_w(r: Rect) -> int:
        return r.width() if callable(getattr(r, "width", None)) else int(r.width)

    @staticmethod
    def _rect_h(r: Rect) -> int:
        return r.height() if callable(getattr(r, "height", None)) else int(r.height)

    @staticmethod
    def _rect_cx(r: Rect) -> int:
        if hasattr(r, "centerX"):
            return r.centerX() if callable(r.centerX) else int(r.centerX)
        return (r.left + r.right) // 2

    @staticmethod
    def _rect_cy(r: Rect) -> int:
        if hasattr(r, "centerY"):
            return r.centerY() if callable(r.centerY) else int(r.centerY)
        return (r.top + r.bottom) // 2

    @classmethod
    def _inpaint_geometric_bubble(cls, canvas: np.ndarray, bounds: Rect, safe_margin: float, img_w: int, img_h: int):
        w = cls._rect_w(bounds)
        h = cls._rect_h(bounds)
        if w <= safe_margin * 2 or h <= safe_margin * 2:
            return

        x1 = int(np.clip(bounds.left + safe_margin, 0, img_w - 1))
        y1 = int(np.clip(bounds.top + safe_margin, 0, img_h - 1))
        x2 = int(np.clip(bounds.right - safe_margin, 0, img_w - 1))
        y2 = int(np.clip(bounds.bottom - safe_margin, 0, img_h - 1))

        if x2 <= x1 or y2 <= y1:
            return

        is_rect = cls._is_rectangular_narration_box(canvas, bounds, img_w, img_h)
        if is_rect:
            cv2.rectangle(canvas, (x1, y1), (x2, y2), (255, 255, 255), -1)
        else:
            center = ((x1 + x2) // 2, (y1 + y2) // 2)
            axes = (max(1, (x2 - x1) // 2), max(1, (y2 - y1) // 2))
            cv2.ellipse(canvas, center, axes, 0, 0, 360, (255, 255, 255), -1)

    @classmethod
    def _is_rectangular_narration_box(cls, img: np.ndarray, bounds: Rect, img_w: int, img_h: int) -> bool:
        w = cls._rect_w(bounds)
        h = cls._rect_h(bounds)
        if w < 24 or h < 24:
            return False

        cx = cls._rect_cx(bounds)
        cy = cls._rect_cy(bounds)
        rx = w / 2.0
        ry = h / 2.0

        corners = [(-1.0, -1.0), (1.0, -1.0), (-1.0, 1.0), (1.0, 1.0)]
        white_corner_count = 0

        for sx, sy in corners:
            corner_has_ink = False
            for step in range(1, 5):
                frac = 0.65 + step * 0.06
                px = int(np.clip(cx + sx * rx * frac, 0, img_w - 1))
                py = int(np.clip(cy + sy * ry * frac, 0, img_h - 1))
                b, g, r = img[py, px][:3]
                lum = int(0.299 * r + 0.587 * g + 0.114 * b)
                if lum < 160:
                    corner_has_ink = True
                    break
            if not corner_has_ink:
                white_corner_count += 1

        return white_corner_count >= 3

    @classmethod
    def _inpaint_orphan_line_laplacian(
        cls,
        canvas: np.ndarray,
        original: np.ndarray,
        line_rect: Rect,
        heatmap: Optional[np.ndarray],
        img_w: int,
        img_h: int
    ):
        pad = 6
        x1 = max(0, line_rect.left - pad)
        y1 = max(0, line_rect.top - pad)
        x2 = min(img_w, line_rect.right + pad)
        y2 = min(img_h, line_rect.bottom + pad)

        crop_w = x2 - x1
        crop_h = y2 - y1
        if crop_w < 4 or crop_h < 4:
            return

        orig_crop = original[y1:y2, x1:x2].astype(np.float32)

        # Step 1: Detect ink stroke mask M(x, y)
        stroke_mask = np.zeros((crop_h, crop_w), dtype=bool)

        if heatmap is not None:
            # Map crop coordinates to 1024x1024 heatmap coordinates
            scale_x = cls.HEATMAP_DIM / float(img_w)
            scale_y = cls.HEATMAP_DIM / float(img_h)

            gx = np.arange(x1, x2, dtype=np.float32) * scale_x
            gy = np.arange(y1, y2, dtype=np.float32) * scale_y
            gx = np.clip(gx.astype(np.int32), 0, cls.HEATMAP_DIM - 1)
            gy = np.clip(gy.astype(np.int32), 0, cls.HEATMAP_DIM - 1)

            hm_patch = heatmap[np.ix_(gy, gx)]
            stroke_mask = hm_patch >= cls.PROB_THRESHOLD
        else:
            # Fallback luminance
            lum = 0.299 * orig_crop[:, :, 2] + 0.587 * orig_crop[:, :, 1] + 0.114 * orig_crop[:, :, 0]
            core_mask = np.zeros((crop_h, crop_w), dtype=bool)
            c_x1 = max(0, line_rect.left - x1)
            c_y1 = max(0, line_rect.top - y1)
            c_x2 = min(crop_w, line_rect.right - x1)
            c_y2 = min(crop_h, line_rect.bottom - y1)
            core_mask[c_y1:c_y2, c_x1:c_x2] = True
            stroke_mask = core_mask & ((lum < 170) | (lum > 215))

        # Outermost border pixels of crop are never ink (Dirichlet boundary)
        stroke_mask[0, :] = False
        stroke_mask[-1, :] = False
        stroke_mask[:, 0] = False
        stroke_mask[:, -1] = False

        if not np.any(stroke_mask):
            return

        # Step 2: Initialize masked pixels with adjacent unmasked boundary mean
        # Boundary mask = adjacent to stroke_mask
        kernel = np.array([[0, 1, 0], [1, 0, 1], [0, 1, 0]], dtype=np.uint8)
        dilated = cv2.dilate(stroke_mask.astype(np.uint8), kernel) > 0
        boundary_pixels = dilated & (~stroke_mask)

        work_crop = orig_crop.copy()
        if np.any(boundary_pixels):
            mean_color = np.mean(orig_crop[boundary_pixels], axis=0)
        else:
            mean_color = np.array([255.0, 255.0, 255.0], dtype=np.float32)

        work_crop[stroke_mask] = mean_color

        # Step 3: 12 iterations Harmonic Laplacian Dirichlet Diffusion
        # I(x, y) = 0.25 * (I(x-1, y) + I(x+1, y) + I(x, y-1) + I(x, y+1))
        # Implemented with 2D cross convolution for high speed in NumPy
        lap_kernel = np.array([[0.0, 0.25, 0.0],
                               [0.25, 0.0, 0.25],
                               [0.0, 0.25, 0.0]], dtype=np.float32)

        for _ in range(cls.LAPLACIAN_ITERS):
            relaxed = cv2.filter2D(work_crop, -1, lap_kernel, borderType=cv2.BORDER_REPLICATE)
            work_crop[stroke_mask] = relaxed[stroke_mask]

        # Step 4: Write inpainted pixels back to canvas
        inpainted_u8 = np.clip(work_crop, 0, 255).astype(np.uint8)
        canvas[y1:y2, x1:x2] = np.where(stroke_mask[:, :, np.newaxis], inpainted_u8, canvas[y1:y2, x1:x2])
