import json
import math

with open('.aaa/crunch_export/crunch_telemetry.json', encoding='utf-8') as f:
    data = json.load(f)

ctd_lines = data['module_1_ctd']['lines']
bubbles = data['module_1_ctd']['bubbles']

class Rect:
    def __init__(self, left, top, right, bottom):
        self.left = int(left)
        self.top = int(top)
        self.right = int(right)
        self.bottom = int(bottom)
    def width(self): return self.right - self.left
    def height(self): return self.bottom - self.top
    def centerX(self): return (self.left + self.right) // 2
    def centerY(self): return (self.top + self.bottom) // 2
    def contains(self, x, y):
        return self.left <= x <= self.right and self.top <= y <= self.bottom
    def copy(self): return Rect(self.left, self.top, self.right, self.bottom)
    def __repr__(self): return f"[{self.left}, {self.top}, {self.right}, {self.bottom}]"

class TextLineItem:
    def __init__(self, id, rect, category, orientation="VERTICAL"):
        self.id = id
        self.rect = rect.copy()
        self.category = category
        self.orientation = orientation
    def copy(self, rect=None, category=None):
        return TextLineItem(self.id, rect if rect else self.rect.copy(), category if category else self.category, self.orientation)
    def __repr__(self):
        return f"Line(id={self.id}, cat={self.category}, rect={self.rect}, w={self.rect.width()}, h={self.rect.height()})"

rawBoxes = [Rect(l['left'], l['top'], l['right'], l['bottom']) for l in ctd_lines]
bubbleRegions = [Rect(b['left'], b['top'], b['right'], b['bottom']) for b in bubbles]

# Distinct bubbles
allBubbleRects = list(bubbleRegions)
distinctBubbles = []
for b in allBubbleRects:
    already = False
    for other in distinctBubbles:
        interL = max(other.left, b.left)
        interT = max(other.top, b.top)
        interR = min(other.right, b.right)
        interB = min(other.bottom, b.bottom)
        if interR > interL and interB > interT:
            interArea = (interR - interL) * (interB - interT)
            minArea = min(other.width() * other.height(), b.width() * b.height())
            if minArea > 0 and interArea / minArea > 0.60:
                already = True
                break
    if not already:
        distinctBubbles.append(b)

# TextCategorizer
bubbled = []
orphan = []
sfx = []
assigned = [False] * len(rawBoxes)

for bubble in distinctBubbles:
    insideIndices = []
    for idx in range(len(rawBoxes)):
        if assigned[idx]: continue
        box = rawBoxes[idx]
        cx = box.centerX()
        cy = box.centerY()
        interL = max(box.left, bubble.left)
        interT = max(box.top, bubble.top)
        interR = min(box.right, bubble.right)
        interB = min(box.bottom, bubble.bottom)
        interArea = max(0, interR - interL) * max(0, interB - interT)
        boxArea = box.width() * box.height()
        isInsideArea = boxArea > 0 and (interArea / boxArea >= 0.50)
        if bubble.contains(cx, cy) and isInsideArea:
            insideIndices.append(idx)
    for idx in insideIndices:
        assigned[idx] = True
        bubbled.append(TextLineItem(0, rawBoxes[idx], "BUBBLED"))

def clusterAdjacentBoxes(boxes):
    visited = [False] * len(boxes)
    clusters = []
    for i in range(len(boxes)):
        if visited[i]: continue
        visited[i] = True
        cluster = [boxes[i]]
        queue = [i]
        while queue:
            curr = queue.pop(0)
            b1 = boxes[curr]
            span = max(b1.width(), b1.height()) * 1.5
            for j in range(len(boxes)):
                if visited[j]: continue
                b2 = boxes[j]
                dx = max(0, max(b1.left, b2.left) - min(b1.right, b2.right))
                dy = max(0, max(b1.top, b2.top) - min(b1.bottom, b2.bottom))
                if dx <= span and dy <= span:
                    visited[j] = True
                    cluster.append(b2)
                    queue.append(j)
        clusters.append(cluster)
    return clusters

orphanIndices = [i for i in range(len(rawBoxes)) if not assigned[i]]
rawNonBubbledBoxes = [rawBoxes[i] for i in orphanIndices]
nonBubbledClusters = clusterAdjacentBoxes(rawNonBubbledBoxes)

m1_5_boxes = data['module_1_5_categorize']['boxes']
m15_map = {}
for b in m1_5_boxes:
    m15_map[(b['left'], b['top'], b['right'], b['bottom'])] = b['category']

for cluster in nonBubbledClusters:
    sfxVotes = sum(1 for box in cluster if m15_map.get((box.left, box.top, box.right, box.bottom)) == "SFX")
    clusterCategory = "SFX" if (len(cluster) > 0 and sfxVotes / len(cluster) >= 0.40) else "ORPHAN"
    for box in cluster:
        item = TextLineItem(0, box, clusterCategory)
        if clusterCategory == "ORPHAN":
            orphan.append(item)
        else:
            sfx.append(item)

validCategorizedBoxes = bubbled + orphan + sfx
sw = sorted([b.rect.width() for b in validCategorizedBoxes if b.rect.width() >= 8 and b.rect.height() >= 8])
medianCharW = max(16.0, min(48.0, float(sw[len(sw)//2]))) if sw else 24.0

def resolveNestedBoxes(boxes):
    result = [b.copy() for b in boxes]
    changed = True
    passes = 0
    while changed and passes < 10:
        changed = False
        passes += 1
        for i in range(len(result)):
            for j in range(i + 1, len(result)):
                a = result[i]
                b = result[j]
                interL = max(a.left, b.left)
                interT = max(a.top, b.top)
                interR = min(a.right, b.right)
                interB = min(a.bottom, b.bottom)
                if interR > interL and interB > interT:
                    interArea = (interR - interL) * (interB - interT)
                    aArea = a.width() * a.height()
                    bArea = b.width() * b.height()
                    aInB = aArea > 0 and interArea / aArea >= 0.40
                    bInA = bArea > 0 and interArea / bArea >= 0.40
                    if aInB or bInA:
                        unionRect = Rect(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom))
                        result.pop(j)
                        result[i] = unionRect
                        changed = True
                        break
            if changed: break
    return result

def suppressOrMergeFurigana(boxes):
    if len(boxes) < 2: return boxes, 0
    sw = sorted([b.width() for b in boxes])
    medianW = max(14.0, min(40.0, float(sw[len(sw)//2])))
    main = []
    furi = []
    for b in boxes:
        if b.width() < 0.48 * medianW and b.height() > 8:
            furi.append(b)
        else:
            main.append(b)
    if not furi or not main: return boxes, 0
    merged = [b.copy() for b in main]
    for fb in furi:
        fcx = fb.centerX()
        parent = None
        minDist = 1e9
        for col in merged:
            vOv = max(0, min(col.bottom, fb.bottom) - max(col.top, fb.top))
            hDist = abs(col.centerX() - fcx)
            if vOv > 0 and hDist <= medianW * 1.5:
                if hDist < minDist:
                    minDist = hDist
                    parent = col
        if parent:
            idx = merged.index(parent)
            merged[idx] = Rect(min(parent.left, fb.left), min(parent.top, fb.top), max(parent.right, fb.right), max(parent.bottom, fb.bottom))
        else:
            merged.append(fb)
    return merged, len(furi)

class BubbleCol:
    def __init__(self, b):
        self.members = [b]
        self.left = b.left; self.top = b.top; self.right = b.right; self.bottom = b.bottom
        self.cx = float(b.centerX())

def stitchBubbleColumns(boxes, bubble, globalCharW):
    if not boxes: return []
    clipped = []
    for b in boxes:
        interL = max(b.left, bubble.left)
        interT = max(b.top, bubble.top)
        interR = min(b.right, bubble.right)
        interB = min(b.bottom, bubble.bottom)
        if interR > interL and interB > interT and (interR - interL) >= 6 and (interB - interT) >= 6:
            clipped.append(Rect(interL, interT, interR, interB))
    if not clipped: return []
    dedup = resolveNestedBoxes(clipped)
    singleChar = [b for b in dedup if 14 <= b.width() <= 44 and b.height() >= 12]
    if singleChar:
        sw = sorted([b.width() for b in singleChar])
        localCharW = max(18.0, min(38.0, float(sw[len(sw)//2])))
    else:
        localCharW = max(18.0, min(38.0, globalCharW))
    
    expanded = []
    for b in dedup:
        isMulti = b.width() >= int(localCharW * 1.65) and b.height() >= int(b.width() * 1.35)
        if isMulti:
            halfW = b.width() // 2
            rightCol = Rect(b.left + halfW, b.top, b.right, b.bottom)
            leftCol = Rect(b.left, b.top, b.left + halfW, b.bottom)
            expanded.append(rightCol)
            expanded.append(leftCol)
        else:
            expanded.append(b)
    cleaned, _ = suppressOrMergeFurigana(expanded)
    sortedBoxes = sorted(cleaned, key=lambda b: (-b.centerX(), b.top))
    cols = []
    for b in sortedBoxes:
        bcx = float(b.centerX())
        bestCol = None
        bestDist = 1e9
        for col in cols:
            colW = col.right - col.left
            hOv = max(0, min(col.right, b.right) - max(col.left, b.left))
            cxDist = abs(bcx - col.cx)
            corridorOk = (cxDist <= max(localCharW * 0.65, colW * 0.60)) or (b.width() > 0 and hOv >= min(b.width(), colW) * 0.35)
            newW = max(col.right, b.right) - min(col.left, b.left)
            if corridorOk and newW <= localCharW * 1.65:
                if cxDist < bestDist:
                    bestDist = cxDist
                    bestCol = col
        if bestCol:
            bestCol.members.append(b)
            bestCol.left = min(bestCol.left, b.left)
            bestCol.right = max(bestCol.right, b.right)
            bestCol.top = min(bestCol.top, b.top)
            bestCol.bottom = max(bestCol.bottom, b.bottom)
            bestCol.cx = sum(m.centerX() for m in bestCol.members) / len(bestCol.members)
        else:
            cols.append(BubbleCol(b))
    
    changed = True
    passes = 0
    while changed and passes < 8 and len(cols) > 1:
        changed = False
        passes += 1
        for i in range(len(cols)):
            for j in range(i+1, len(cols)):
                c1 = cols[i]; c2 = cols[j]
                cxDist = abs(c1.cx - c2.cx)
                unionW = max(c1.right, c2.right) - min(c1.left, c2.left)
                if cxDist <= localCharW * 0.65 or unionW <= localCharW * 1.55:
                    c1.members.extend(c2.members)
                    c1.left = min(c1.left, c2.left)
                    c1.right = max(c1.right, c2.right)
                    c1.top = min(c1.top, c2.top)
                    c1.bottom = max(c1.bottom, c2.bottom)
                    c1.cx = sum(m.centerX() for m in c1.members) / len(c1.members)
                    cols.pop(j)
                    changed = True
                    break
            if changed: break
    
    finalLines = []
    for col in cols:
        line = Rect(max(col.left, bubble.left), max(col.top, bubble.top), min(col.right, bubble.right), min(col.bottom, bubble.bottom))
        if line.width() >= 8 and line.height() >= 12:
            finalLines.append(line)
    
    finalLines.sort(key=lambda l: l.centerX())
    for i in range(len(finalLines) - 1):
        leftCol = finalLines[i]
        rightCol = finalLines[i+1]
        if leftCol.right > rightCol.left:
            midX = (leftCol.right + rightCol.left) // 2
            leftCol.right = midX
            rightCol.left = midX
    return finalLines

class ActiveCol:
    def __init__(self, cx, left, right, bottomEdge, members, maxWidth):
        self.cx = cx
        self.xLeft = left
        self.xRight = right
        self.bottomEdge = bottomEdge
        self.members = list(members)
        self.maxWidth = maxWidth

def stitchColumnBoxes(boxes, bitmapWidth=1280, bitmapHeight=1799, isInsideBubble=False):
    if not boxes: return []
    if len(boxes) == 1: return [boxes[0].copy()]
    valid = [b for b in boxes if b.width() >= 8 and b.height() >= 8]
    sw = sorted([b.width() for b in valid])
    medianCharW = max(16.0, min(48.0, float(sw[len(sw)//2]))) if sw else 24.0
    dedup = resolveNestedBoxes(boxes)
    xStripW = max(8, int(medianCharW))
    sortedBoxes = sorted(dedup, key=lambda b: (- (b.centerX() // xStripW), b.top))
    cols = []
    for b in sortedBoxes:
        boxCx = float(b.centerX())
        bestCol = None
        bestDist = 1e9
        for col in cols:
            tol = medianCharW * 0.55 if isInsideBubble else medianCharW * 0.45
            if boxCx < col.xLeft - tol or boxCx > col.xRight + tol: continue
            newLeft = min(col.xLeft, b.left)
            newRight = max(col.xRight, b.right)
            if (newRight - newLeft) > col.maxWidth: continue
            if not isInsideBubble:
                gap = max(0, b.top - col.bottomEdge)
                if gap > medianCharW * 3.5: continue
            dist = abs(boxCx - col.cx)
            if dist < bestDist:
                bestDist = dist
                bestCol = col
        if bestCol:
            bestCol.members.append(b)
            bestCol.xLeft = min(bestCol.xLeft, b.left)
            bestCol.xRight = max(bestCol.xRight, b.right)
            bestCol.bottomEdge = max(bestCol.bottomEdge, b.bottom)
            bestCol.maxWidth = max(bestCol.maxWidth, float(bestCol.xRight - bestCol.xLeft))
            bestCol.cx = sum(m.centerX() for m in bestCol.members) / len(bestCol.members)
        else:
            cols.append(ActiveCol(
                boxCx, b.left, b.right, b.bottom, [b],
                max(medianCharW * 1.65, float(b.width()) + 4.0)
            ))
    rawLines = []
    for col in cols:
        if not col.members: continue
        sortedCol = sorted(col.members, key=lambda m: m.top)
        colW = sorted([m.width() for m in sortedCol])
        colMedW = max(10.0, min(medianCharW * 1.6, float(colW[len(colW)//2])))
        colHalfW = max(5, int(colMedW * 0.60))
        curL = sortedCol[0].left; curT = sortedCol[0].top; curR = sortedCol[0].right; curB = sortedCol[0].bottom
        runningCx = float(sortedCol[0].centerX()); memberCount = 1
        for k in range(1, len(sortedCol)):
            nxt = sortedCol[k]
            dy = max(0, nxt.top - curB)
            if dy <= (1e9 if isInsideBubble else medianCharW * 3.5):
                curL = min(curL, nxt.left); curT = min(curT, nxt.top); curR = max(curR, nxt.right); curB = max(curB, nxt.bottom)
                runningCx = (runningCx * memberCount + nxt.centerX()) / (memberCount + 1)
                memberCount += 1
            else:
                cx = int(runningCx)
                clampL = max(0, min(bitmapWidth, cx - colHalfW))
                clampR = max(0, min(bitmapWidth, cx + colHalfW))
                rawLines.append(Rect(max(curL, clampL), curT, min(curR, clampR), curB))
                curL = nxt.left; curT = nxt.top; curR = nxt.right; curB = nxt.bottom
                runningCx = float(nxt.centerX()); memberCount = 1
        cx = int(runningCx)
        clampL = max(0, min(bitmapWidth, cx - colHalfW))
        clampR = max(0, min(bitmapWidth, cx + colHalfW))
        rawLines.append(Rect(max(curL, clampL), curT, min(curR, clampR), curB))
    return rawLines

assignedMap = [False] * len(validCategorizedBoxes)
resultBubbled = []
for b_idx, bubble in enumerate(distinctBubbles):
    inside = []
    for idx, item in enumerate(validCategorizedBoxes):
        if assignedMap[idx]: continue
        box = item.rect
        cx = box.centerX()
        cy = box.centerY()
        interL = max(box.left, bubble.left)
        interT = max(box.top, bubble.top)
        interR = min(box.right, bubble.right)
        interB = min(box.bottom, bubble.bottom)
        interArea = max(0, interR - interL) * max(0, interB - interT)
        boxArea = box.width() * box.height()
        if bubble.contains(cx, cy) and boxArea > 0 and (interArea / boxArea >= 0.50):
            inside.append(idx)
    for idx in inside: assignedMap[idx] = True
    clusterBoxes = [validCategorizedBoxes[idx].rect for idx in inside]
    stitched = stitchBubbleColumns(clusterBoxes, bubble, medianCharW)
    resultBubbled.extend(stitched)

orphanIndices2 = [i for i in range(len(validCategorizedBoxes)) if not assignedMap[i]]
orphanBoxes2 = [validCategorizedBoxes[i].rect for i in orphanIndices2 if validCategorizedBoxes[i].category != "SFX"]
sfxBoxes2 = [validCategorizedBoxes[i].rect for i in orphanIndices2 if validCategorizedBoxes[i].category == "SFX"]

resultOrphan = stitchColumnBoxes(orphanBoxes2)
resultSfx = stitchColumnBoxes(sfxBoxes2)

bubbledItems = [TextLineItem(0, r, "BUBBLED") for r in resultBubbled]
orphanItems = [TextLineItem(0, r, "ORPHAN") for r in resultOrphan]
sfxItems = [TextLineItem(0, r, "SFX") for r in resultSfx]

allCombined = bubbledItems + orphanItems + sfxItems
print(f"Before passes: total={len(allCombined)} (bubbled={len(bubbledItems)}, orphan={len(orphanItems)}, sfx={len(sfxItems)})")

# Check lines in region 50/51
for it in allCombined:
    if 100 <= it.rect.centerX() <= 180 and 1200 <= it.rect.centerY() <= 1350:
        print("Region 50/51 item:", it)
