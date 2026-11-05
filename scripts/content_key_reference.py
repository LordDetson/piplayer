"""An independent reference of content key v1 (docs/track-identification.md §3.1).

The expected keys in the content key tests come from this script, not from the code they test. It reads whole files
and parses them its own way on purpose: no bounded walks and no TagLib, only the definition of the layout.

Prints one line per file: the key in hex, the format token, the value of the length field and the file.

Run: python scripts/content_key_reference.py FILE...
"""
import hashlib
import pathlib
import sys

CHUNK = 65536
OGG_TOKENS = ("ogg-vorbis", "opus")


def key_of(token, length, payload):
    digest = hashlib.sha256()
    digest.update(bytes([1]))
    digest.update(token.encode("ascii") + b"\0")
    digest.update(length.to_bytes(8, "big", signed=True))
    if token in OGG_TOKENS:
        if len(payload) < 2 * CHUNK:
            digest.update(payload)
        else:
            digest.update(payload[:CHUNK])
            digest.update(payload[-CHUNK:])
    elif len(payload) < 3 * CHUNK:
        digest.update(payload)
    else:
        middle = (len(payload) - CHUNK) // 2
        digest.update(payload[:CHUNK])
        digest.update(payload[middle:middle + CHUNK])
        digest.update(payload[-CHUNK:])
    return digest.hexdigest()


def id3v2_end(data):
    if data[:3] != b"ID3" or len(data) < 10:
        return 0
    size = (data[6] << 21) | (data[7] << 14) | (data[8] << 7) | data[9]
    return 10 + size + (10 if data[5] & 0x10 else 0)


def trailing_tags_start(data):
    """Where ID3v1 and APE tags at the end of the file start."""
    end = len(data)
    # The end of an APE tag of 131 bytes can read TAG in the place of ID3v1.
    if end >= 128 and data[end - 128:end - 125] == b"TAG" and data[end - 131:end - 123] != b"APETAGEX":
        end -= 128
    if end >= 32 and data[end - 32:end - 24] == b"APETAGEX":
        size = int.from_bytes(data[end - 20:end - 16], "little")
        flags = int.from_bytes(data[end - 12:end - 8], "little")
        end -= size + (32 if flags & 0x80000000 else 0)
    return end


MPEG1_L3 = [0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0]
MPEG2_L3 = [0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0]
RATES = {3: [44100, 48000, 32000], 2: [22050, 24000, 16000], 0: [11025, 12000, 8000]}


def is_adts(data, at):
    return (data[at + 1] >> 1) & 3 == 0


def frame_length(data, at):
    """The length of the Layer III or ADTS frame at [at], or 0 when no frame header is there."""
    if at + 6 > len(data) or data[at] != 0xFF or data[at + 1] & 0xE0 != 0xE0:
        return 0
    if is_adts(data, at):
        if data[at + 1] & 0xF0 != 0xF0:
            return 0
        return ((data[at + 3] & 3) << 11) | (data[at + 4] << 3) | (data[at + 5] >> 5)
    version = (data[at + 1] >> 3) & 3
    if version == 1 or (data[at + 1] >> 1) & 3 != 1:
        return 0
    bitrate_index = data[at + 2] >> 4
    rate_index = (data[at + 2] >> 2) & 3
    if rate_index == 3:
        return 0
    kbps = (MPEG1_L3 if version == 3 else MPEG2_L3)[bitrate_index]
    if kbps == 0:
        return 0
    samples = 1152 if version == 3 else 576
    return samples // 8 * kbps * 1000 // RATES[version][rate_index] + ((data[at + 2] >> 1) & 1)


def consistent(data, a, b):
    """Whether a frame header at [b] has the sync, version, layer and sample rate of the one at [a]."""
    return (b + 4 <= len(data) and data[a] == data[b] and (data[a + 1] & 0xFE) == (data[b + 1] & 0xFE)
            and (data[a + 2] & 0x0C) == (data[b + 2] & 0x0C))


def counts(data, at):
    """A frame counts when another of its kind follows it."""
    length = frame_length(data, at)
    return length > 0 and consistent(data, at, at + length)


def mpeg_payload(data):
    """Returns (token, payload), or None without three frames in a row within 1 MiB after a leading ID3v2 tag."""
    begin = id3v2_end(data)
    probe = next((at for at in range(begin, min(begin + (1 << 20), len(data)))
                  if counts(data, at) and counts(data, at + frame_length(data, at))), None)
    if probe is None:
        return None
    start = next(at for at in range(begin, len(data)) if counts(data, at))
    end_of_tags = trailing_tags_start(data)
    if is_adts(data, start):
        return ("adts", data[start:end_of_tags]) if start < end_of_tags else None
    # The last frame that counts ends where the last frame starts.
    at = len(data) - 2
    while at >= 0 and not (at < end_of_tags and counts(data, at)):
        at -= 1
    last = at + frame_length(data, at)
    end = min(last + frame_length(data, last), end_of_tags)
    first = data[start:start + frame_length(data, start)]
    if b"Xing" in first or b"Info" in first or b"VBRI" in first:
        start += len(first)
    return ("mp3", data[start:end]) if start < end else None


def flac_payload(data):
    at = id3v2_end(data)
    if data[at:at + 4] != b"fLaC":
        return None
    at += 4
    while True:
        header = data[at:at + 4]
        if len(header) < 4:
            return None
        at += 4 + int.from_bytes(header[1:4], "big")
        if header[0] & 0x80:
            break
    end = trailing_tags_start(data)
    return data[at:end] if at < end else None


def mp4_payload(data):
    at = 0
    payload = bytearray()
    while at + 8 <= len(data):
        size = int.from_bytes(data[at:at + 4], "big")
        kind = data[at + 4:at + 8]
        header = 8
        if size == 1:
            if at + 16 > len(data):
                break
            size = int.from_bytes(data[at + 8:at + 16], "big")
            header = 16
        elif size == 0:
            size = len(data) - at
        if size < header:
            break
        if kind == b"mdat":
            payload += data[at + header:at + size]
        at += size
    return bytes(payload) or None


def wav_payload(data):
    at = 12
    while at + 8 <= len(data):
        size = int.from_bytes(data[at + 4:at + 8], "little")
        if data[at:at + 4] == b"data":
            return data[at + 8:at + 8 + size] or None
        at += 8 + size + (size & 1)
    return None


def ogg_crc(page):
    crc = 0
    for byte in page:
        crc ^= byte << 24
        for _ in range(8):
            crc = ((crc << 1) ^ 0x04C11DB7) & 0xFFFFFFFF if crc & 0x80000000 else (crc << 1) & 0xFFFFFFFF
    return crc


def ogg_pages(data):
    """Every CRC-valid page in file order: flags, granule, serial, lacing values, body."""
    at = 0
    while True:
        at = data.find(b"OggS", at)
        if at < 0 or at + 27 > len(data):
            return
        count = data[at + 26]
        lacing = data[at + 27:at + 27 + count]
        body_at = at + 27 + count
        body_size = sum(lacing)
        page = bytearray(data[at:body_at + body_size])
        if len(page) == 27 + count + body_size:
            stored = int.from_bytes(page[22:26], "little")
            page[22:26] = bytes(4)
            if ogg_crc(page) == stored:
                yield (data[at + 5], int.from_bytes(data[at + 6:at + 14], "little", signed=True),
                       data[at + 14:at + 18], lacing, data[body_at:body_at + body_size])
                at = body_at + body_size
                continue
        at += 1


def ogg_payload(data):
    """Returns (token, final granule, payload), or None when the file is not a single Vorbis or Opus stream."""
    pages = list(ogg_pages(data))
    if not pages:
        return None
    first_body = pages[0][4]
    if first_body[:7] == b"\x01vorbis":
        token, needed = "ogg-vorbis", 3
    elif first_body[:8] == b"OpusHead":
        token, needed = "opus", 2
    else:
        return None
    serial = pages[0][2]
    if any(page[2] != serial for page in pages) or any(page[0] & 2 for page in pages[1:]):
        return None
    packets = 0
    audio_from = None
    for index, page in enumerate(pages):
        packets += sum(1 for value in page[3] if value < 255)
        if packets >= needed:
            audio_from = index + 1
            break
    if audio_from is None or audio_from >= len(pages):
        return None
    granules = [page[1] for page in pages[audio_from:] if page[1] != -1]
    if not granules:
        return None
    return token, granules[-1], b"".join(page[4] for page in pages[audio_from:])


def reference(path):
    """The format comes from the content alone, as in the app: a file's name never changes its key."""
    data = pathlib.Path(path).read_bytes()
    at = id3v2_end(data)
    located = None
    if data[:4] == b"OggS":
        located = ogg_payload(data)
    elif data[at:at + 4] == b"fLaC":
        payload = flac_payload(data)
        located = ("flac", len(payload), payload) if payload else None
    elif data[4:8] == b"ftyp":
        payload = mp4_payload(data)
        located = ("mp4", len(payload), payload) if payload else None
    elif data[:4] == b"RIFF" and data[8:12] == b"WAVE":
        payload = wav_payload(data)
        located = ("wav", len(payload), payload) if payload else None
    else:
        found = mpeg_payload(data)
        located = (found[0], len(found[1]), found[1]) if found else None
    token, length, payload = located or ("file", len(data), data)
    return key_of(token, length, payload), token, length


def main():
    for path in sys.argv[1:]:
        key, token, length = reference(path)
        print(key, token, length, pathlib.PurePath(path).as_posix())


if __name__ == "__main__":
    main()
