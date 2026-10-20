"""Generates the playback fixtures of the app's instrumented tests (docs/playback.md §1.7).

Containers the decoding fixtures do not cover, each a 2 s tone of 1 kHz: raw AAC (ADTS), AMR narrowband and
wideband (raw and in 3GP), Opus in WebM and Vorbis in Matroska. They must play to the end.

A tagged MP3 (title, artist and album in ID3v2.3, Cyrillic included) for the media index tests.

Seek fixtures: 12 one-second segments, segment k a tone of 400 + 100 * k Hz at half scale, and white noise added to
the last 6, so that a variable-bitrate encoder spends about three times more bits on the second half. A seek to the
middle of segment k must play that tone. MP3 comes three ways: variable bitrate with a Xing header and its table of
contents, the same with the table removed (so the extractor has to build an index), and constant bitrate with no
header at all; plus raw AAC (ADTS), seeked by its average frame size, and raw AMR narrowband in one mode, seeked
by the size of its first frame (docs/playback.md §1.3).

The files are made by ffmpeg from synthetic sound, so they carry no third-party rights. The expected values live in
the tests (app/src/androidTest/.../playback/); keep them in step.

Run from the repository root: python scripts/generate_playback_fixtures.py
"""
import pathlib
import shutil
import subprocess
import sys

OUT = pathlib.Path("app/src/androidTest/assets/playback")
SEGMENTS = 12
BASE_HZ = 400
STEP_HZ = 100

# name, sample rate, channel layout, encoder and muxer arguments
FORMATS = [
    ("aac-adts-44100-stereo.aac", 44100, "stereo", ["-c:a", "aac", "-b:a", "96k", "-f", "adts"]),
    ("amr-nb-8000-mono.amr", 8000, "mono", ["-c:a", "libopencore_amrnb", "-b:a", "12.2k", "-f", "amr"]),
    ("amr-wb-16000-mono.awb", 16000, "mono", ["-c:a", "libvo_amrwbenc", "-b:a", "23.85k", "-f", "amr"]),
    ("amr-nb-8000-mono.3gp", 8000, "mono", ["-c:a", "libopencore_amrnb", "-b:a", "12.2k", "-f", "3gp"]),
    ("amr-wb-16000-mono.3gp", 16000, "mono", ["-c:a", "libvo_amrwbenc", "-b:a", "23.85k", "-f", "3gp"]),
    ("opus-48000-stereo.webm", 48000, "stereo", ["-c:a", "libopus", "-b:a", "96k", "-f", "webm"]),
    ("vorbis-44100-stereo.mka", 44100, "stereo", ["-c:a", "libvorbis", "-q:a", "4", "-f", "matroska"]),
]

SEEK_FIXTURES = [
    ("seek-mp3-vbr-xing.mp3", 44100, "stereo", ["-c:a", "libmp3lame", "-q:a", "2", "-f", "mp3"]),
    ("seek-mp3-cbr-no-header.mp3", 44100, "stereo",
     ["-c:a", "libmp3lame", "-b:a", "128k", "-write_xing", "0", "-f", "mp3"]),
    ("seek-aac-adts.aac", 44100, "stereo", ["-c:a", "aac", "-b:a", "64k", "-f", "adts"]),
    ("seek-amr-nb.amr", 8000, "mono", ["-c:a", "libopencore_amrnb", "-b:a", "12.2k", "-f", "amr"]),
]

MP3_BITRATES_KBPS = [0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320]
MP3_SAMPLE_RATES = [44100, 48000, 32000]


def ffmpeg(arguments):
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", *arguments], check=True)


def common_output_arguments(rate):
    return ["-ar", str(rate), "-map_metadata", "-1", "-id3v2_version", "0", "-fflags", "+bitexact",
            "-flags:a", "+bitexact"]


def encode_tone(target, rate, layout, encoder):
    """A 2 s tone of 1 kHz at half scale in every channel."""
    channels = 2 if layout == "stereo" else 1
    expression = "|".join(["0.5*sin(2*PI*1000*t)"] * channels)
    tone = f"aevalsrc=exprs='{expression}':channel_layout={layout}:sample_rate={rate}:duration=2"
    ffmpeg(["-f", "lavfi", "-i", tone, *common_output_arguments(rate), *encoder, str(target)])


def encode_tagged(target):
    """A 2 s tone of 1 kHz with ID3v2.3 tags."""
    tone = "aevalsrc=exprs='0.5*sin(2*PI*1000*t)|0.5*sin(2*PI*1000*t)':channel_layout=stereo:sample_rate=44100:duration=2"
    ffmpeg(["-f", "lavfi", "-i", tone, "-ar", "44100", "-map_metadata", "-1", "-fflags", "+bitexact",
            "-flags:a", "+bitexact", "-c:a", "libmp3lame", "-b:a", "128k", "-id3v2_version", "3",
            "-metadata", "title=Ёжик в тумане", "-metadata", "artist=PiPlayer Fixtures", "-metadata", "album=Тесты",
            "-f", "mp3", str(target)])


def encode_segments(target, rate, layout, encoder):
    """The seek signal: one tone per second, noise over the second half."""
    tones = "+".join(
        f"between(t,{k},{k + 1})*0.5*sin(2*PI*{BASE_HZ + STEP_HZ * k}*t)" for k in range(SEGMENTS))
    gate = f"gte(t,{SEGMENTS // 2})"
    signal = f"aevalsrc=exprs='{tones}':channel_layout=mono:sample_rate={rate}:duration={SEGMENTS}"
    noise = f"anoisesrc=color=white:amplitude=0.25:sample_rate={rate}:duration={SEGMENTS}:seed=7"
    graph = (f"[1]volume='{gate}':eval=frame[n];[0][n]amix=inputs=2:normalize=0,"
             f"aformat=channel_layouts={layout}")
    ffmpeg(["-f", "lavfi", "-i", signal, "-f", "lavfi", "-i", noise, "-filter_complex", graph,
            *common_output_arguments(rate), *encoder, str(target)])


def mp3_frame_size(header):
    """The size of an MPEG-1 Layer III frame from its 4-byte header."""
    if header[0] != 0xFF or header[1] & 0xFE != 0xFA:
        sys.exit("the file does not start with an MPEG-1 Layer III frame")
    bitrate = MP3_BITRATES_KBPS[header[2] >> 4] * 1000
    sample_rate = MP3_SAMPLE_RATES[(header[2] >> 2) & 3]
    padding = (header[2] >> 1) & 1
    return 144 * bitrate // sample_rate + padding


def remove_xing_table(source, target):
    """Copies [source] without the table of contents of its Xing header; the frame keeps its size."""
    data = bytearray(source.read_bytes())
    frame_size = mp3_frame_size(data[:4])
    tag = data.find(b"Xing", 0, frame_size)
    if tag < 0:
        sys.exit(f"{source} has no Xing header in its first frame")
    flags = int.from_bytes(data[tag + 4:tag + 8], "big")
    if not flags & 4:
        sys.exit(f"{source} has no table of contents")
    table = tag + 8 + 4 * bin(flags & 3).count("1")
    data[tag + 4:tag + 8] = (flags & ~4).to_bytes(4, "big")
    del data[table:table + 100]
    data[frame_size - 100:frame_size - 100] = bytes(100)
    target.write_bytes(data)


def main():
    if shutil.which("ffmpeg") is None:
        sys.exit("ffmpeg is not on PATH")
    OUT.mkdir(parents=True, exist_ok=True)
    for name, rate, layout, encoder in FORMATS:
        encode_tone(OUT / name, rate, layout, encoder)
    for name, rate, layout, encoder in SEEK_FIXTURES:
        encode_segments(OUT / name, rate, layout, encoder)
    remove_xing_table(OUT / "seek-mp3-vbr-xing.mp3", OUT / "seek-mp3-vbr-xing-without-toc.mp3")
    encode_tagged(OUT / "tagged-44100-stereo.mp3")
    for path in sorted(OUT.iterdir()):
        print(f"{path.name}: {path.stat().st_size} bytes")


if __name__ == "__main__":
    main()
