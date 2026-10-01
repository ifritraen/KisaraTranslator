from dataclasses import dataclass, field
from typing import List, Tuple, Optional
import numpy as np

@dataclass
class Rect:
    left: int
    top: int
    right: int
    bottom: int
    id: Optional[int] = None

    def __post_init__(self):
        self.left = int(self.left)
        self.top = int(self.top)
        self.right = int(self.right)
        self.bottom = int(self.bottom)

    def width(self) -> int:
        return max(0, self.right - self.left)

    def height(self) -> int:
        return max(0, self.bottom - self.top)

    def centerX(self) -> int:
        return (self.left + self.right) // 2

    def centerY(self) -> int:
        return (self.top + self.bottom) // 2

    def contains(self, x: int, y: int) -> bool:
        return self.left <= x <= self.right and self.top <= y <= self.bottom

    def contains_rect(self, other: 'Rect') -> bool:
        return (self.left <= other.left and self.top <= other.top and
                self.right >= other.right and self.bottom >= other.bottom)

    def __eq__(self, other: object) -> bool:
        if not isinstance(other, Rect):
            return False
        return (self.left == other.left and self.top == other.top and
                self.right == other.right and self.bottom == other.bottom)

    def __hash__(self) -> int:
        return hash((self.left, self.top, self.right, self.bottom))

    def copy(self) -> 'Rect':
        return Rect(self.left, self.top, self.right, self.bottom, id=self.id)

    def set(self, l: int, t: int, r: int, b: int):
        self.left = int(l)
        self.top = int(t)
        self.right = int(r)
        self.bottom = int(b)

    def to_dict(self):
        return {
            "left": self.left,
            "top": self.top,
            "right": self.right,
            "bottom": self.bottom,
            "width": self.width(),
            "height": self.height(),
        }

    def __repr__(self):
        return f"[{self.left}, {self.top}, {self.right}, {self.bottom}]"

@dataclass
class TextLineItem:
    id: int
    rect: Rect
    category: str = "BUBBLED"  # "BUBBLED", "ORPHAN", "SFX"
    orientation: str = "VERTICAL"
    confidence: float = 1.0

    def copy(self, id: Optional[int] = None, rect: Optional[Rect] = None, category: Optional[str] = None):
        return TextLineItem(
            id=self.id if id is None else id,
            rect=self.rect.copy() if rect is None else rect,
            category=self.category if category is None else category,
            orientation=self.orientation,
            confidence=self.confidence
        )

    def to_dict(self):
        return {
            "id": self.id,
            "category": self.category,
            "orientation": self.orientation,
            "confidence": self.confidence,
            "rect": self.rect.to_dict()
        }

@dataclass
class BubbleMask:
    rect: Rect
    mask: np.ndarray  # boolean or uint8 2D array in local rect coords (h, w)
    width: int
    height: int
    fill_area: int
    id: Optional[int] = None
    is_lobe: bool = False
    parent_bubble_index: Optional[int] = None

@dataclass
class CrunchPartitionItem:
    bubble_index: int
    bubble_rect: Rect
    is_conjoined: bool
    conf_conj: float
    p1_raw: Optional[Tuple[int, int]] = None
    p2_raw: Optional[Tuple[int, int]] = None
    p1_snapped: Optional[Tuple[int, int]] = None
    p2_snapped: Optional[Tuple[int, int]] = None
    candidate_points: List[Tuple[int, int]] = field(default_factory=list)
    lobe_a_line_ids: List[int] = field(default_factory=list)
    lobe_b_line_ids: List[int] = field(default_factory=list)
    split_lines: List[TextLineItem] = field(default_factory=list)

    def to_dict(self):
        return {
            "bubble_index": self.bubble_index,
            "bubble_rect": self.bubble_rect.to_dict(),
            "is_conjoined": self.is_conjoined,
            "conf_conj": self.conf_conj,
            "p1_raw": self.p1_raw,
            "p2_raw": self.p2_raw,
            "p1_snapped": self.p1_snapped,
            "p2_snapped": self.p2_snapped,
            "candidate_count": len(self.candidate_points),
            "lobe_a_line_ids": self.lobe_a_line_ids,
            "lobe_b_line_ids": self.lobe_b_line_ids,
            "split_line_count": len(self.split_lines)
        }
