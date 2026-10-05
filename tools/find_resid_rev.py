# -*- coding: utf-8 -*-
"""Reverse lookup: given a resource ID (hex), print type/name/value."""
import zipfile, struct, sys, os
TARGET = int(sys.argv[1], 16)
t_pkg, t_type, t_entry = (TARGET >> 24) & 0xff, (TARGET >> 16) & 0xff, TARGET & 0xffff
z = zipfile.ZipFile(os.environ.get('MELODY_APK', 'melody.apk'))
data = z.read('resources.arsc')

def parse_string_pool(buf, off):
    ctype, hsize, size = struct.unpack_from('<HHI', buf, off)
    stringCount, styleCount, flags, stringsStart, stylesStart = struct.unpack_from('<IIIII', buf, off+8)
    isUTF8 = (flags & (1<<8)) != 0
    offsets = struct.unpack_from('<%dI' % stringCount, buf, off+28)
    abs_start = off + stringsStart
    out = []
    for so in offsets:
        p = abs_start + so
        try:
            if isUTF8:
                n = buf[p]
                p += 2 if n & 0x80 else 1
                blen = buf[p]
                if blen & 0x80:
                    blen = ((blen & 0x7f) << 8) | buf[p+1]
                    p += 2
                else:
                    p += 1
                out.append(buf[p:p+blen].decode('utf-8', 'ignore'))
            else:
                n = struct.unpack_from('<H', buf, p)[0]
                if n & 0x8000:
                    n = ((n & 0x7fff) << 16) | struct.unpack_from('<H', buf, p+2)[0]
                    p += 4
                else:
                    p += 2
                out.append(buf[p:p+n*2].decode('utf-16-le', 'ignore'))
        except Exception:
            out.append('')
    return out, off + size

ctype, hsize, size, packageCount = struct.unpack_from('<HHII', data, 0)
pos = hsize
global_strings, pos = parse_string_pool(data, pos)
found = []
for _ in range(packageCount):
    ptype, phsize, psize = struct.unpack_from('<HHI', data, pos)
    pkg_id = struct.unpack_from('<I', data, pos+8)[0]
    typeStringsOff, lastPublicType, keyStringsOff, lastPublicKey = struct.unpack_from('<IIII', data, pos+268)
    type_names, _ = parse_string_pool(data, pos + typeStringsOff)
    key_names, _ = parse_string_pool(data, pos + keyStringsOff)
    pkg_end = pos + psize
    cur = pos + phsize
    while cur < pkg_end:
        ctype2, hsize2, size2 = struct.unpack_from('<HHI', data, cur)
        if ctype2 == 0x0001:
            cur += size2; continue
        if ctype2 == 0x0201:
            tid = data[cur+8]
            entryCount, entriesStart = struct.unpack_from('<II', data, cur+12)
            offs_base = cur + hsize2
            vals_base = cur + entriesStart
            if pkg_id == t_pkg and tid == t_type and t_entry < entryCount:
                eo = struct.unpack_from('<I', data, offs_base + t_entry*4)[0]
                if eo != 0xFFFFFFFF:
                    ep = vals_base + eo
                    esize, eflags, keyIdx = struct.unpack_from('<HHI', data, ep)
                    ename = key_names[keyIdx] if keyIdx < len(key_names) else '?'
                    tname = type_names[tid-1] if tid-1 < len(type_names) else str(tid)
                    vp = ep + 8
                    vsize, res0, dtype, vdata = struct.unpack_from('<HBBi', data, vp)
                    val = ''
                    if dtype == 0x03:
                        val = global_strings[vdata] if 0 <= vdata < len(global_strings) else ''
                    else:
                        val = 'type=0x%02x data=0x%x' % (dtype, vdata & 0xffffffff)
                    found.append((hex(TARGET), tname, ename, val))
        cur += size2
    pos = pkg_end
for f in found: print(f)
print('total:', len(found))
