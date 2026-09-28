import cv2
import numpy as np
import onnxruntime as ort
from typing import List, Tuple
from .types import Rect, BubbleMask

class BubbleSegmenter:
    """
    1:1 Port of BubbleSegmentationEngine.kt for PC Simulator.
    Runs Manga109 YOLO11-seg ONNX inference and returns pixel-accurate bubble masks and bounding boxes.
    """
    INPUT_SIZE = 1024

    def __init__(self, model_path: str):
        opts = ort.SessionOptions()
        opts.intra_op_num_threads = 2
        opts.inter_op_num_threads = 1
        opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        self.session = ort.InferenceSession(model_path, opts)
        self.input_name = self.session.get_inputs()[0].name

    def detect_masks(
        self,
        image_bgr: np.ndarray,
        conf_thresh: float = 0.35,
        iou_thresh: float = 0.45
    ) -> List[BubbleMask]:
        orig_h, orig_w = image_bgr.shape[:2]

        resized = cv2.resize(image_bgr, (self.INPUT_SIZE, self.INPUT_SIZE), interpolation=cv2.INTER_LINEAR)
        rgb = cv2.cvtColor(resized, cv2.COLOR_BGR2RGB).astype(np.float32) * (1.0 / 255.0)
        blob = np.transpose(rgb, (2, 0, 1))[np.newaxis, ...]  # [1, 3, 1024, 1024]

        outputs = self.session.run(None, {self.input_name: blob})
        o0 = outputs[0][0]  # shape: (37, 21504)
        protos = outputs[1][0]  # shape: (32, 256, 256)

        num_anchors = o0.shape[1]
        confidences = o0[4, :]
        valid_indices = np.where(confidences >= conf_thresh)[0]
        if len(valid_indices) == 0:
            return []

        raw_dets = []
        for idx in valid_indices:
            cx, cy, bw, bh = o0[0:4, idx]
            conf = float(confidences[idx])
            coeffs = o0[5:37, idx]
            raw_dets.append((float(cx), float(cy), float(bw), float(bh), conf, coeffs))

        # NMS with IoU + Containment Gate (1:1 with BubbleSegmentationEngine.kt)
        raw_dets.sort(key=lambda d: d[4], reverse=True)
        suppress = [False] * len(raw_dets)
        kept = []

        for i in range(len(raw_dets)):
            if suppress[i]:
                continue
            kept.append(raw_dets[i])
            a = raw_dets[i]
            a_w, a_h = a[2], a[3]
            a_area = a_w * a_h

            for j in range(i + 1, len(raw_dets)):
                if suppress[j]:
                    continue
                b = raw_dets[j]
                b_w, b_h = b[2], b[3]
                b_area = b_w * b_h

                ix1 = max(a[0] - a_w / 2.0, b[0] - b_w / 2.0)
                iy1 = max(a[1] - a_h / 2.0, b[1] - b_h / 2.0)
                ix2 = min(a[0] + a_w / 2.0, b[0] + b_w / 2.0)
                iy2 = min(a[1] + a_h / 2.0, b[1] + b_h / 2.0)

                inter = max(0.0, ix2 - ix1) * max(0.0, iy2 - iy1)
                union = a_area + b_area - inter
                iou = (inter / union) if union > 0 else 0.0
                smaller_area = min(a_area, b_area)
                containment = (inter / smaller_area) if smaller_area > 0 else 0.0

                if iou > iou_thresh or containment > 0.60:
                    suppress[j] = True

        scale_x = orig_w / float(self.INPUT_SIZE)
        scale_y = orig_h / float(self.INPUT_SIZE)
        nm, ph, pw = protos.shape  # 32, 256, 256
        proto_flat = protos.reshape(nm, -1)  # (32, 256*256)

        results: List[BubbleMask] = []

        for det in kept:
            cx, cy, bw, bh, conf, coeffs = det
            pad_x = (bw * 0.005 + 1.0) * scale_x
            pad_y = (bh * 0.005 + 1.0) * scale_y

            x1 = max(0, min(orig_w - 1, int((cx - bw / 2.0) * scale_x - pad_x)))
            y1 = max(0, min(orig_h - 1, int((cy - bh / 2.0) * scale_y - pad_y)))
            x2 = max(x1 + 1, min(orig_w, int((cx + bw / 2.0) * scale_x + pad_x)))
            y2 = max(y1 + 1, min(orig_h, int((cy + bh / 2.0) * scale_y + pad_y)))

            bw_orig = x2 - x1
            bh_orig = y2 - y1
            if bw_orig < 8 or bh_orig < 8:
                continue

            # Proto crop in 256-space
            px1 = max(0, min(pw - 1, int(x1 / float(orig_w) * pw)))
            py1 = max(0, min(ph - 1, int(y1 / float(orig_h) * ph)))
            px2 = max(px1 + 1, min(pw, int(x2 / float(orig_w) * pw) + 1))
            py2 = max(py1 + 1, min(ph, int(y2 / float(orig_h) * ph) + 1))
            cp_w = px2 - px1
            cp_h = py2 - py1

            # Linear combination: (cp_h, cp_w)
            sub_proto = protos[:, py1:py2, px1:px2]  # (32, cp_h, cp_w)
            dot = np.tensordot(coeffs, sub_proto, axes=(0, 0))  # (cp_h, cp_w)
            proto_mask = (1.0 / (1.0 + np.exp(-dot))) >= 0.45
            if np.sum(proto_mask) < 10:
                continue

            # Scale mask to bounding box size (bw_orig, bh_orig)
            mask_resized = cv2.resize(
                proto_mask.astype(np.uint8),
                (bw_orig, bh_orig),
                interpolation=cv2.INTER_NEAREST
            ).astype(bool)

            fill_area = int(np.sum(mask_resized))
            if fill_area < 50:
                continue

            results.append(BubbleMask(
                rect=Rect(x1, y1, x2, y2),
                mask=mask_resized,
                width=bw_orig,
                height=bh_orig,
                fill_area=fill_area
            ))

        return results
