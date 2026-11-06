#!/usr/bin/env python3
"""
Pure-Python converter from FontForge SplineFontDB (freefont/FreeMono.sfd)
to a standard TrueType font (SpellyFreeMono.ttf) with Format 4 + Format 12 cmap.
Requires only Python 3 standard library.
"""
import math
import re
import struct
import sys
from pathlib import Path


def parse_sfd(sfd_path: str):
    text = Path(sfd_path).read_text(encoding="utf-8")
    raw_chars = text.split("\nStartChar: ")[1:]
    by_gid = {}
    by_uni = {}
    raw_list = []

    for block in raw_chars:
        lines = block.splitlines()
        name = lines[0].strip()
        enc_m = re.search(r"^Encoding:\s+(-?\d+)\s+(-?\d+)\s+(-?\d+)", block, re.M)
        width_m = re.search(r"^Width:\s+(-?\d+)", block, re.M)
        if not enc_m:
            continue
        enc, uni, gid = map(int, enc_m.groups())
        width = int(width_m.group(1)) if width_m else 600

        refers = []
        for rm in re.finditer(
            r"^Refer:\s+(\d+)\s+(-?\d+)\s+\S+\s+"
            r"([-\d.]+)\s+([-\d.]+)\s+([-\d.]+)\s+([-\d.]+)\s+([-\d.]+)\s+([-\d.]+)",
            block,
            re.M,
        ):
            ref_gid = int(rm.group(1))
            ref_uni = int(rm.group(2))
            matrix = tuple(map(float, rm.groups()[2:]))
            refers.append((ref_gid, ref_uni, matrix))

        contours = []
        spline_m = re.search(r"^SplineSet\n(.*?)^EndSplineSet", block, re.M | re.S)
        if spline_m:
            contours = parse_splineset(spline_m.group(1))

        entry = {
            "name": name,
            "uni": uni,
            "gid": gid,
            "width": width,
            "contours": contours,
            "refers": refers,
        }
        raw_list.append(entry)
        by_gid[gid] = entry
        if uni >= 0:
            by_uni[uni] = entry

    # Resolve Refer composite glyphs into concrete contours
    def get_contours(entry):
        result = [list(c) for c in entry["contours"]]
        for ref_gid, ref_uni, (m00, m01, m10, m11, dx, dy) in entry["refers"]:
            target = by_uni.get(ref_uni) or by_gid.get(ref_gid)
            if not target:
                continue
            det = m00 * m11 - m01 * m10
            for c in get_contours(target):
                tc = []
                for x, y, on_curve in c:
                    nx = m00 * x + m10 * y + dx
                    ny = m01 * x + m11 * y + dy
                    tc.append((nx, ny, on_curve))
                if det < 0:
                    tc.reverse()
                result.append(tc)
        return result

    resolved = []
    for entry in raw_list:
        if entry["uni"] >= 0:
            resolved.append(
                {
                    "name": entry["name"],
                    "uni": entry["uni"],
                    "width": entry["width"],
                    "contours": get_contours(entry),
                }
            )
    resolved.sort(key=lambda g: g["uni"])
    return resolved


def cubic_to_quadratics(p0, p1, p2, p3, steps=4):
    """Approximate cubic Bezier with `steps` quadratic Bezier segments."""
    pts = []
    for i in range(steps):
        t0 = i / steps
        t1 = (i + 1) / steps
        tm = 0.5 * (t0 + t1)

        def eval_cubic(t):
            u = 1.0 - t
            x = (
                u * u * u * p0[0]
                + 3 * u * u * t * p1[0]
                + 3 * u * t * t * p2[0]
                + t * t * t * p3[0]
            )
            y = (
                u * u * u * p0[1]
                + 3 * u * u * t * p1[1]
                + 3 * u * t * t * p2[1]
                + t * t * t * p3[1]
            )
            return (x, y)

        q0 = eval_cubic(t0)
        qm = eval_cubic(tm)
        q1 = eval_cubic(t1)
        # Quadratic control point C such that 0.25*q0 + 0.5*C + 0.25*q1 = qm
        cx = 2.0 * qm[0] - 0.5 * (q0[0] + q1[0])
        cy = 2.0 * qm[1] - 0.5 * (q0[1] + q1[1])
        pts.append((cx, cy, False))
        pts.append((q1[0], q1[1], True))
    return pts


def parse_splineset(spline_text: str):
    contours = []
    current = []
    cur_pt = (0.0, 0.0)
    start_pt = (0.0, 0.0)

    for raw_line in spline_text.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("Named:"):
            continue
        tokens = line.split()
        # Find operator ('m', 'l', 'c')
        op_idx = -1
        for idx, tok in enumerate(tokens):
            if tok in ("m", "l", "c"):
                op_idx = idx
                break
        if op_idx == -1:
            continue
        op = tokens[op_idx]
        nums = [float(x) for x in tokens[:op_idx]]
        if op == "m" and len(nums) >= 2:
            if len(current) >= 3:
                contours.append(current)
            start_pt = (nums[0], nums[1])
            cur_pt = start_pt
            current = [(start_pt[0], start_pt[1], True)]
        elif op == "l" and len(nums) >= 2:
            pt = (nums[0], nums[1])
            if math.hypot(pt[0] - cur_pt[0], pt[1] - cur_pt[1]) > 1e-3:
                current.append((pt[0], pt[1], True))
                cur_pt = pt
        elif op == "c" and len(nums) >= 6:
            c1 = (nums[0], nums[1])
            c2 = (nums[2], nums[3])
            p3 = (nums[4], nums[5])
            quad_pts = cubic_to_quadratics(cur_pt, c1, c2, p3, steps=2)
            current.extend(quad_pts)
            cur_pt = p3

    if len(current) >= 3:
        contours.append(current)

    # Remove duplicate closing point if identical to start point
    cleaned = []
    for c in contours:
        if len(c) > 2 and math.hypot(c[-1][0] - c[0][0], c[-1][1] - c[0][1]) < 0.5:
            c = c[:-1]
        if len(c) >= 3:
            cleaned.append(c)
    return cleaned


def encode_glyf_entry(contours):
    if not contours:
        return b"", (0, 0, 0, 0), 0

    end_pts = []
    all_pts = []
    for c in contours:
        for x, y, on_curve in c:
            all_pts.append((int(round(x)), int(round(y)), bool(on_curve)))
        end_pts.append(len(all_pts) - 1)

    if not all_pts:
        return b"", (0, 0, 0, 0), 0

    xs = [p[0] for p in all_pts]
    ys = [p[1] for p in all_pts]
    xmin, ymin, xmax, ymax = min(xs), min(ys), max(xs), max(ys)

    header = struct.pack(">hhhhh", len(contours), xmin, ymin, xmax, ymax)
    end_pts_bin = struct.pack(f">{len(end_pts)}H", *end_pts)
    instr_len = struct.pack(">H", 0)

    flags = bytearray()
    x_bytes = bytearray()
    y_bytes = bytearray()

    prev_x = 0
    prev_y = 0
    for x, y, on_curve in all_pts:
        dx = x - prev_x
        dy = y - prev_y
        prev_x = x
        prev_y = y

        flag = 0x01 if on_curve else 0x00
        if dx == 0:
            flag |= 0x10
        elif -255 <= dx <= 255:
            flag |= 0x02
            if dx > 0:
                flag |= 0x10
            x_bytes.append(abs(dx))
        else:
            x_bytes.extend(struct.pack(">h", dx))

        if dy == 0:
            flag |= 0x20
        elif -255 <= dy <= 255:
            flag |= 0x04
            if dy > 0:
                flag |= 0x20
            y_bytes.append(abs(dy))
        else:
            y_bytes.extend(struct.pack(">h", dy))

        flags.append(flag)

    data = header + end_pts_bin + instr_len + bytes(flags) + bytes(x_bytes) + bytes(y_bytes)
    if len(data) % 2 != 0:
        data += b"\x00"
    return data, (xmin, ymin, xmax, ymax), len(all_pts)


def build_cmap(uni_to_gid):
    # Subtable 1: Format 4 (BMP codepoints <= 0xFFFF)
    bmp_items = sorted((u, g) for u, g in uni_to_gid.items() if 0 <= u <= 0xFFFF)
    segments = []
    for u, g in bmp_items:
        if segments and segments[-1][1] + 1 == u and segments[-1][2] + (u - segments[-1][0]) == g:
            segments[-1][1] = u
        else:
            segments.append([u, u, g])
    if not segments or segments[-1][1] != 0xFFFF:
        segments.append([0xFFFF, 0xFFFF, 0])

    seg_count = len(segments)
    seg_count_x2 = seg_count * 2
    max_pow2 = 1 << (int(math.log2(seg_count)) if seg_count > 0 else 0)
    search_range = 2 * max_pow2
    entry_selector = int(math.log2(max_pow2)) if max_pow2 > 0 else 0
    range_shift = seg_count_x2 - search_range

    end_codes = [s[1] for s in segments]
    start_codes = [s[0] for s in segments]
    id_deltas = [0 if s[0] == 0xFFFF else (s[2] - s[0]) & 0xFFFF for s in segments]
    id_range_offsets = [0] * seg_count

    fmt4_body = (
        struct.pack(f">{seg_count}H", *end_codes)
        + struct.pack(">H", 0)
        + struct.pack(f">{seg_count}H", *start_codes)
        + struct.pack(f">{seg_count}H", *id_deltas)
        + struct.pack(f">{seg_count}H", *id_range_offsets)
    )
    fmt4_len = 14 + len(fmt4_body)
    fmt4 = (
        struct.pack(">HHHHHHH", 4, fmt4_len, 0, seg_count_x2, search_range, entry_selector, range_shift)
        + fmt4_body
    )

    # Subtable 2: Format 12 (Full Unicode including U+1F512, U+1F513)
    all_items = sorted(uni_to_gid.items())
    groups = []
    for u, g in all_items:
        if groups and groups[-1][1] + 1 == u and groups[-1][2] + (u - groups[-1][0]) == g:
            groups[-1][1] = u
        else:
            groups.append([u, u, g])

    groups_bin = b"".join(struct.pack(">III", s, e, g) for s, e, g in groups)
    fmt12_len = 16 + len(groups_bin)
    fmt12 = struct.pack(">HHIII", 12, 0, fmt12_len, 0, len(groups)) + groups_bin

    # cmap header with 3 records: (0,3)->fmt4, (3,1)->fmt4, (3,10)->fmt12
    header_size = 4 + 3 * 8
    fmt4_offset = header_size
    fmt12_offset = header_size + len(fmt4)
    cmap_header = struct.pack(
        ">HH" "HHI" "HHI" "HHI",
        0,
        3,
        0,
        3,
        fmt4_offset,
        3,
        1,
        fmt4_offset,
        3,
        10,
        fmt12_offset,
    )
    return cmap_header + fmt4 + fmt12


def build_name_table(family="SpellyFreeMono", style="Regular"):
    records = [
        (1, family),
        (2, style),
        (3, f"{family}-{style}"),
        (4, family),
        (5, "Version 1.0"),
        (6, family),
    ]
    string_storage = bytearray()
    name_records = bytearray()
    for name_id, val in records:
        # Platform 3 (Windows), Encoding 1 (Unicode BMP), Language 0x0409 (en-US)
        encoded = val.encode("utf-16-be")
        offset = len(string_storage)
        string_storage.extend(encoded)
        name_records.extend(struct.pack(">HHHHHH", 3, 1, 0x0409, name_id, len(encoded), offset))

    count = len(records)
    storage_offset = 6 + len(name_records)
    return struct.pack(">HHH", 0, count, storage_offset) + bytes(name_records) + bytes(string_storage)


def calc_checksum(data: bytes) -> int:
    padded = data + b"\x00" * ((4 - len(data) % 4) % 4)
    total = 0
    for (val,) in struct.iter_unpack(">I", padded):
        total = (total + val) & 0xFFFFFFFF
    return total


def build_ttf(glyphs, out_path: str):
    # Prepend .notdef (gid 0)
    notdef_contours = [[(50, 0, True), (50, 600, True), (550, 600, True), (550, 0, True)]]
    all_glyphs = [{"name": ".notdef", "uni": -1, "width": 600, "contours": notdef_contours}] + glyphs

    glyf_chunks = []
    loca_offsets = [0]
    hmtx_entries = []
    uni_to_gid = {}

    global_xmin, global_ymin, global_xmax, global_ymax = 0, -200, 600, 800
    max_pts = 0
    max_contours = 0

    for gid, g in enumerate(all_glyphs):
        g_bin, (xmin, ymin, xmax, ymax), n_pts = encode_glyf_entry(g["contours"])
        glyf_chunks.append(g_bin)
        loca_offsets.append(loca_offsets[-1] + len(g_bin))
        hmtx_entries.append(struct.pack(">Hh", g["width"], xmin))
        if g["uni"] >= 0:
            uni_to_gid[g["uni"]] = gid
            if g["uni"] == 0x1F512:
                uni_to_gid[0xE092] = gid
            elif g["uni"] == 0x1F513:
                uni_to_gid[0xE093] = gid
        if n_pts > 0:
            global_xmin = min(global_xmin, xmin)
            global_ymin = min(global_ymin, ymin)
            global_xmax = max(global_xmax, xmax)
            global_ymax = max(global_ymax, ymax)
            max_pts = max(max_pts, n_pts)
            max_contours = max(max_contours, len(g["contours"]))

    num_glyphs = len(all_glyphs)
    glyf_table = b"".join(glyf_chunks)
    loca_table = struct.pack(f">{len(loca_offsets)}I", *loca_offsets)
    hmtx_table = b"".join(hmtx_entries)
    cmap_table = build_cmap(uni_to_gid)
    name_table = build_name_table()

    head_table = struct.pack(
        ">IIIIHHIIIIhhhhHHhhh",
        0x00010000,  # version 1.0
        0x00010000,  # fontRevision
        0,           # checkSumAdjustment (filled later)
        0x5F0F3CF5,  # magicNumber
        0x000B,      # flags
        1000,        # unitsPerEm
        0, 0,        # created (64-bit)
        0, 0,        # modified (64-bit)
        global_xmin,
        global_ymin,
        global_xmax,
        global_ymax,
        0,           # macStyle
        8,           # lowestRecPPEM
        2,           # fontDirectionHint
        1,           # indexToLocFormat (1 = long 32-bit offsets)
        0,           # glyphDataFormat
    )

    hhea_table = struct.pack(
        ">IhhhHhhhhhhHHHHhH",
        0x00010000,  # version
        800,         # ascent
        -200,        # descent
        0,           # lineGap
        600,         # advanceWidthMax
        global_xmin, # minLeftSideBearing
        0,           # minRightSideBearing
        global_xmax, # xMaxExtent
        1,           # caretSlopeRise
        0,           # caretSlopeRun
        0,           # caretOffset
        0, 0, 0, 0,  # reserved
        0,           # metricDataFormat
        num_glyphs,  # numberOfHMetrics
    )

    maxp_table = struct.pack(
        ">IH" "HHHHHHHHHHHHH",
        0x00010000,   # version 1.0
        num_glyphs,
        max_pts,
        max_contours,
        0, 0,         # composite pts/contours
        2,            # maxZones
        0,            # maxTwilightPoints
        0,            # maxStorage
        0,            # maxFunctionDefs
        0,            # maxInstructionDefs
        64,           # maxStackElements
        0,            # maxSizeOfInstructions
        0,            # maxComponentElements
        0,            # maxComponentDepth
    )

    os2_table = struct.pack(
        ">HhHHH" "hhhhhhhhhhh" "10B" "IIII" "4s" "HHH" "hhhHH",
        3,            # version
        600,          # xAvgCharWidth
        400,          # usWeightClass (Normal)
        5,            # usWidthClass (Medium)
        0,            # fsType
        650, 699, 47, 140,   # ySubscript*
        650, 699, -163, 479, # ySuperscript*
        49, 258,      # yStrikeout*
        0,            # sFamilyClass
        2, 15, 4, 9, 2, 2, 5, 2, 4, 4, # panose (monospace)
        0x00000001, 0x00000000, 0x00000000, 0x00000000, # ulUnicodeRange
        b"GNU ",      # achVendID
        0x0040,       # fsSelection (REGULAR)
        0x0020,       # usFirstCharIndex
        0xFFFF,       # usLastCharIndex
        800,          # sTypoAscender
        -200,         # sTypoDescender
        0,            # sTypoLineGap
        800,          # usWinAscent
        200,          # usWinDescent
    ) + struct.pack(">IIhhhhH", 1, 0, 430, 563, 0, 0, 0)

    post_table = struct.pack(
        ">IIhhI" "IIII",
        0x00030000,   # Format 3.0 (no postscript glyph names needed)
        0,            # italicAngle
        -100,         # underlinePosition
        50,           # underlineThickness
        1,            # isFixedPitch (1 = monospace)
        0, 0, 0, 0,
    )

    tables = {
        b"OS/2": os2_table,
        b"cmap": cmap_table,
        b"glyf": glyf_table,
        b"head": head_table,
        b"hhea": hhea_table,
        b"hmtx": hmtx_table,
        b"loca": loca_table,
        b"maxp": maxp_table,
        b"name": name_table,
        b"post": post_table,
    }

    sorted_tags = sorted(tables.keys())
    num_tables = len(sorted_tags)
    max_pow2 = 1 << int(math.log2(num_tables))
    search_range = max_pow2 * 16
    entry_selector = int(math.log2(max_pow2))
    range_shift = num_tables * 16 - search_range

    sfnt_header = struct.pack(">IHHHH", 0x00010000, num_tables, search_range, entry_selector, range_shift)
    offset = 12 + num_tables * 16
    dir_entries = bytearray()
    body_chunks = bytearray()
    head_offset = 0

    for tag in sorted_tags:
        raw = tables[tag]
        if tag == b"head":
            head_offset = offset
        chk = calc_checksum(raw)
        dir_entries.extend(struct.pack(">4sIII", tag, chk, offset, len(raw)))
        padded = raw + b"\x00" * ((4 - len(raw) % 4) % 4)
        body_chunks.extend(padded)
        offset += len(padded)

    full_font = bytearray(sfnt_header + dir_entries + body_chunks)
    total_chk = calc_checksum(full_font)
    checksum_adj = (0xB1B0AFBA - total_chk) & 0xFFFFFFFF
    struct.pack_into(">I", full_font, head_offset + 8, checksum_adj)

    Path(out_path).parent.mkdir(parents=True, exist_ok=True)
    Path(out_path).write_bytes(full_font)
    print(f"Generated {out_path} ({len(full_font)} bytes, {num_glyphs} glyphs)")


if __name__ == "__main__":
    sfd_file = sys.argv[1] if len(sys.argv) > 1 else "/home/travis/spellcast/freefont/FreeMono.sfd"
    out_file = sys.argv[2] if len(sys.argv) > 2 else "/home/travis/spellcast/android/app/src/main/assets/SpellyFreeMono.ttf"
    glyphs = parse_sfd(sfd_file)
    build_ttf(glyphs, out_file)
