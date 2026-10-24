"""Generates the gapless fixtures of the app's instrumented tests (docs/playback.md §8.2).

One continuous 12 s piece, stereo, 16-bit, computed from a formula (GaplessSignal in the tests computes the same):
- left: a logarithmic sweep from 80 Hz to 6 kHz at 0.25, a 443.7 Hz tone at 0.10, and a chirp at 0.10 whose frequency
  rises from 2 kHz to 12 kHz every half second with its phase kept continuous; the chirp pins an alignment to the
  sample;
- right: a logarithmic sweep from 400 Hz down to 60 Hz at 0.30 and a 97.1 Hz tone at 0.10.

It is split at fixed times into 5 songs, one under a second, none a whole number of any encoder's frames, and encoded as
albums: FLAC, MP3 (LAME, variable bitrate), AAC in M4A with iTunSMPB and no edit list, Opus in Ogg (the piece computed
at 48 kHz), and AAC as ffmpeg writes it (an edit list ending on a short last frame), a known limitation of the player.
Each file's gapless information is checked against the true length of its song; a mismatch stops the script.

fixture.json records each album's files and song lengths, the MD5 of the 44.1 kHz piece (the FLAC album must play
back as exactly that) and, for the ffmpeg AAC album, how late each song ends.

The sound is synthetic, so the files carry no third-party rights. Needs ffmpeg with libmp3lame and libopus, and
AtomicParsley. Run from the repository root: python scripts/generate_gapless_fixtures.py
"""
import array
import hashlib
import json
import math
import pathlib
import shutil
import struct
import subprocess
import sys
import tempfile
import wave

OUT = pathlib.Path("app/src/androidTest/assets/gapless")
SECONDS = 12.0
CUTS = [0.0, 3.0013, 5.6373, 8.9835, 9.4270, SECONDS]
# Encoder frames (MP3 576 and 1152, AAC 1024, Opus 960) and FLAC blocks: no song may be a whole number of them.
FRAME_SIZES = [576, 1152, 1024, 960, 2048, 4096, 4608]
# ffmpeg 9 caps the padding in the LAME tag at 1681, so an MP3 song must not end within 47 samples of a frame.
MIN_MP3_TAIL = 47
COMMON = ["-map_metadata", "-1", "-fflags", "+bitexact", "-flags:a", "+bitexact"]

ALBUMS = {
    # name: (rate, extension, encoder arguments)
    "flac": (44100, "flac", ["-c:a", "flac", "-f", "flac"]),
    "mp3": (44100, "mp3", ["-c:a", "libmp3lame", "-q:a", "2", "-id3v2_version", "0", "-write_id3v1", "0", "-f", "mp3"]),
    "aac": (44100, "m4a", ["-c:a", "aac", "-b:a", "128k", "-use_editlist", "0", "-f", "ipod"]),
    "opus": (48000, "opus", ["-c:a", "libopus", "-b:a", "96k", "-f", "ogg"]),
    "aac-ffmpeg": (44100, "m4a", ["-c:a", "aac", "-b:a", "128k", "-f", "ipod"]),
}


def log_sweep(t, f0, f1):
    k = math.log(f1 / f0) / SECONDS
    return math.sin(2 * math.pi * f0 * (math.exp(k * t) - 1) / k)


def chirp(t):
    """2 kHz to 12 kHz each half second; the phase is the integral of the frequency, so it never jumps."""
    period = 0.5
    cycles = math.floor(t / period)
    rest = t / period - cycles
    phase = 2000 * t + 10000 * period * (cycles + rest * rest) / 2
    return math.sin(2 * math.pi * phase)


def frame(t):
    left = 0.25 * log_sweep(t, 80, 6000) + 0.10 * math.sin(2 * math.pi * 443.7 * t) + 0.10 * chirp(t)
    right = 0.30 * log_sweep(t, 400, 60) + 0.10 * math.sin(2 * math.pi * 97.1 * t)
    return left, right


def quantize(x):
    return max(-32768, min(32767, round(x * 32767)))


def piece(rate):
    pcm = array.array("h")
    for n in range(round(SECONDS * rate)):
        left, right = frame(n / rate)
        pcm.append(quantize(left))
        pcm.append(quantize(right))
    if sys.byteorder != "little":
        pcm.byteswap()
    return pcm


def lengths(rate):
    bounds = [round(cut * rate) for cut in CUTS]
    sizes = [b - a for a, b in zip(bounds, bounds[1:])]
    for size in sizes:
        bad = [f for f in FRAME_SIZES if size % f == 0]
        if bad:
            sys.exit(f"A song of {size} samples at {rate} Hz is a whole number of {bad} frames: move a cut")
        if rate == 44100 and size % 1152 < MIN_MP3_TAIL:
            sys.exit(f"A song of {size} samples ends {size % 1152} samples into an MP3 frame: move a cut")
    return bounds, sizes


def write_wav(path, pcm, rate):
    with wave.open(str(path), "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(pcm.tobytes())


def ffmpeg(arguments):
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", *arguments], check=True)


def boxes(data, start, end):
    """The MP4 boxes in data[start:end]: (type, start of content, end)."""
    while start + 8 <= end:
        size, kind = struct.unpack(">I4s", data[start:start + 8])
        header = 8
        if size == 1:
            size = struct.unpack(">Q", data[start + 8:start + 16])[0]
            header = 16
        elif size == 0:
            size = end - start
        yield kind.decode("latin-1"), start + header, start + size
        start += size


def find(data, path, start=0, end=None):
    """The content range of the first box at path ("moov/trak/mdia"), or None."""
    end = len(data) if end is None else end
    head, _, rest = path.partition("/")
    for kind, content, box_end in boxes(data, start, end):
        if kind == head:
            if not rest:
                return content, box_end
            # meta is a full box: version and flags come before its children.
            return find(data, rest, content + 4 if kind == "meta" else content, box_end)
    return None


def stts(data):
    content, _ = find(data, "moov/trak/mdia/minf/stbl/stts")
    count = struct.unpack(">I", data[content + 4:content + 8])[0]
    return [struct.unpack(">II", data[content + 8 + 8 * i:content + 16 + 8 * i]) for i in range(count)]


def check_mp3(path, size):
    data = path.read_bytes()
    xing = max(data.find(b"Xing", 0, 200), data.find(b"Info", 0, 200))
    flags = struct.unpack(">I", data[xing + 4:xing + 8])[0]
    frames = struct.unpack(">I", data[xing + 8:xing + 12])[0]
    lame = xing + 8 + 4 * bool(flags & 1) + 4 * bool(flags & 2) + 100 * bool(flags & 4) + 4 * bool(flags & 8)
    delay_padding = int.from_bytes(data[lame + 21:lame + 24], "big")
    delay, padding = delay_padding >> 12, delay_padding & 0xFFF
    if frames * 1152 - delay - padding != size:
        sys.exit(f"{path}: the LAME tag says {frames} frames, delay {delay}, padding {padding}, not {size} samples")


def check_flac(path, size):
    data = path.read_bytes()
    total = int.from_bytes(data[8 + 13:8 + 18], "big") & 0xFFFFFFFFF
    if total != size:
        sys.exit(f"{path}: STREAMINFO says {total} samples, not {size}")


def check_opus(path, size):
    data = path.read_bytes()
    head = data.find(b"OpusHead")
    pre_skip = struct.unpack("<H", data[head + 10:head + 12])[0]
    last = data.rfind(b"OggS")
    granule = struct.unpack("<q", data[last + 6:last + 14])[0]
    if granule - pre_skip != size:
        sys.exit(f"{path}: the last granule {granule} minus the pre-skip {pre_skip} is not {size}")


def write_itunsmpb(path, size):
    """Gapless information as iTunes writes it: iTunSMPB holds the priming, the padding and the length."""
    data = path.read_bytes()
    if find(data, "moov/trak/edts"):
        sys.exit(f"{path}: an edit list was written despite -use_editlist 0")
    entries = stts(data)
    frames = sum(count for count, _ in entries)
    delay = 1024
    padding = frames * 1024 - delay - size
    if not 0 <= padding < 2048:
        sys.exit(f"{path}: {frames} frames cannot hold {size} samples after a priming of {delay}")
    value = " " + " ".join(["00000000", f"{delay:08X}", f"{padding:08X}", f"{size:016X}"] + ["00000000"] * 8)
    subprocess.run(["AtomicParsley", str(path), "--rDNSatom", value, "name=iTunSMPB", "domain=com.apple.iTunes",
                    "--overWrite"], check=True, stdout=subprocess.DEVNULL)
    if b"iTunSMPB" not in path.read_bytes():
        sys.exit(f"{path}: AtomicParsley did not write iTunSMPB")


def check_ffmpeg_aac(path, size):
    """How late the song ends: Media3 takes the padding as the frames' length minus the edit's end, which ffmpeg makes
    equal by shortening the last frame in stts, so the rest of that frame plays (docs/playback.md §8.1)."""
    data = path.read_bytes()
    content, _ = find(data, "moov/trak/edts/elst")
    version = data[content]
    count = struct.unpack(">I", data[content + 4:content + 8])[0]
    if count != 1:
        sys.exit(f"{path}: {count} edits")
    if version == 1:
        duration, media_time = struct.unpack(">Qq", data[content + 8:content + 24])
    else:
        duration, media_time = struct.unpack(">Ii", data[content + 8:content + 16])
    movie_scale = struct.unpack(">I", data[find(data, "moov/mvhd")[0] + 12:][:4])[0]
    entries = stts(data)
    last = entries[-1][1]
    total = sum(count * delta for count, delta in entries)
    if media_time != 1024 or round(duration * 44100 / movie_scale) != size or total != media_time + size:
        sys.exit(f"{path}: edit {duration}/{movie_scale} s from {media_time}, stts {entries}: not the layout expected")
    if not last < 1024:
        sys.exit(f"{path}: the last frame is whole ({entries}), so nothing is late")
    return 1024 - last


def main():
    for tool in ("ffmpeg", "AtomicParsley"):
        if shutil.which(tool) is None:
            sys.exit(f"{tool} is not on PATH")
    if OUT.exists():
        shutil.rmtree(OUT)
    manifest = {"seconds": SECONDS, "cuts": CUTS, "albums": {}}
    pieces = {}
    with tempfile.TemporaryDirectory() as temporary:
        work = pathlib.Path(temporary)
        for rate in sorted({rate for rate, _, _ in ALBUMS.values()}):
            pcm = piece(rate)
            bounds, sizes = lengths(rate)
            songs = []
            for number, (start, end) in enumerate(zip(bounds, bounds[1:]), start=1):
                wav = work / f"{rate}-{number}.wav"
                write_wav(wav, pcm[2 * start:2 * end], rate)
                songs.append(wav)
            pieces[rate] = (pcm, sizes, songs)
        for name, (rate, extension, encoder) in ALBUMS.items():
            pcm, sizes, songs = pieces[rate]
            directory = OUT / name
            directory.mkdir(parents=True)
            album = {"rate": rate, "files": [], "frames": sizes}
            late = []
            for number, (wav, size) in enumerate(zip(songs, sizes), start=1):
                target = directory / f"{number}.{extension}"
                ffmpeg(["-i", str(wav), *COMMON, *encoder, str(target)])
                if extension == "flac":
                    check_flac(target, size)
                elif extension == "mp3":
                    check_mp3(target, size)
                elif extension == "opus":
                    check_opus(target, size)
                elif name == "aac":
                    write_itunsmpb(target, size)
                else:
                    late.append(check_ffmpeg_aac(target, size))
                album["files"].append(f"gapless/{name}/{number}.{extension}")
            if name == "flac":
                album["md5"] = hashlib.md5(pcm.tobytes()).hexdigest()
            if late:
                album["late"] = late
            manifest["albums"][name] = album
            print(name, rate, sizes, f"late {late}" if late else "", sum(p.stat().st_size for p in directory.iterdir()))
    (OUT / "fixture.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8", newline="\n")


if __name__ == "__main__":
    main()
