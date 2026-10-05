# -*- coding: utf-8 -*-
"""Find the resource ID of a string resource by its value in resources.arsc."""
import zipfile, struct, sys, os

TARGET = sys.argv[1] if len(sys.argv) > 1 else '降噪效果'
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

# Table header
ctype, hsize, size, packageCount = struct.unpack_from('<HHII', data, 0)
assert ctype == 0x0002, hex(ctype)

pos = hsize  # skip table header -> global string pool
global_strings, pos = parse_string_pool(data, pos)

results = []

# Iterate packages
for _ in range(packageCount):
    ptype, phsize, psize = struct.unpack_from('<HHI', data, pos)
    assert ptype == 0x0200, hex(ptype)
    pkg_id = struct.unpack_from('<I', data, pos+8)[0]
    pkg_name = data[pos+12:pos+12+256].decode('utf-16-le').rstrip('\x00')
    typeStringsOff, lastPublicType, keyStringsOff, lastPublicKey = struct.unpack_from('<IIII', data, pos+268)
    # type id -> type name map
    type_names, _ = parse_string_pool(data, pos + typeStringsOff)
    key_names, _ = parse_string_pool(data, pos + keyStringsOff)
    pkg_end = pos + psize
    cur = pos + phsize
    # skip the two string pools inside package chunk
    while cur < pkg_end:
        ctype2, hsize2, size2 = struct.unpack_from('<HHI', data, cur)
        if ctype2 == 0x0001:
            cur += size2
            continue
        if ctype2 == 0x0201:  # ResTable_type
            # header: type(u16) headerSize(u16) size(u32) id(u8)@8 flags(u8)@9 reserved(u16)
            #         entryCount(u32)@12 entriesStart(u32)@16 config@20
            tid = data[cur+8]
            entryCount, entriesStart = struct.unpack_from('<II', data, cur+12)
            offs_base = cur + hsize2  # uint32 offsets[entryCount]
            vals_base = cur + entriesStart
            tname = type_names[tid-1] if tid-1 < len(type_names) else str(tid)
            for e in range(entryCount):
                eo = struct.unpack_from('<I', data, offs_base + e*4)[0]
                if eo == 0xFFFFFFFF:
                    continue
                ep = vals_base + eo
                esize, eflags, keyIdx = struct.unpack_from('<HHI', data, ep)
                ename = key_names[keyIdx] if keyIdx < len(key_names) else '?'
                vp = ep + 8
                # value: size(u16), res0(u8), dataType(u8), data(u32)
                vsize, res0, dtype, vdata = struct.unpack_from('<HBBi', data, vp)
                if dtype == 0x03:  # TYPE_STRING -> vdata is global string index
                    sval = global_strings[vdata] if 0 <= vdata < len(global_strings) else ''
                    if sval == TARGET:
                        rid = (pkg_id << 24) | (tid << 16) | e
                        results.append((hex(rid), pkg_name, tname, ename, sval))
                elif eflags & 0x0001:  # FLAG_COMPLEX -> skip maps, check parent values
                    pass
        cur += size2
    pos = pkg_end

for r in results:
    print(r)
print('total:', len(results))
