"""Generates the decoding fixtures of the app's instrumented tests.

Each fixture is a 1 kHz tone at half scale in every channel, in one of the formats, sample rates and channel layouts
that the fingerprint decoder must handle (TI §3.2): MP3, AAC, Ogg Vorbis, Opus, FLAC at 96 kHz/24-bit and in 5.1, FLAC
whose frames are larger than the platform decoder's default input buffer and whose header does not say so (written
to a pipe, with a little noise so that the frames do not compress, and their sizes cleared in STREAMINFO), WAV in
16-bit, 24-bit and float, and 11025 Hz mono WAV, which needs no resampling. Edge cases: a FLAC file cut off in the
middle of its last frame, a FLAC file of a single frame, an MP3 whose sample rate rises halfway, and a WAV of 64-bit
floats, which Android has no encoding for. The files are made by ffmpeg from synthetic sound, so they carry no
third-party rights. The expected values live in the tests (app/src/androidTest/.../DecodingFixturesTest.kt and
DecodingEdgeCasesTest.kt); keep them in step.

Run from the repository root: python scripts/generate_decoding_fixtures.py
"""
import pathlib
import shutil
import subprocess
import sys

OUT = pathlib.Path("app/src/androidTest/assets/decoding")
SECONDS = 2
TONE_HZ = 1000
CHANNELS = {"mono": 1, "stereo": 2, "5.1": 6}

# name, sample rate, channel layout, encoder arguments; "pipe" fixtures are written to standard output
FIXTURES = [
    ("mp3-44100-stereo.mp3", 44100, "stereo", ["-c:a", "libmp3lame", "-b:a", "128k"]),
    ("aac-44100-stereo.m4a", 44100, "stereo", ["-c:a", "aac", "-b:a", "128k"]),
    ("vorbis-48000-mono.ogg", 48000, "mono", ["-c:a", "libvorbis", "-q:a", "4"]),
    ("opus-48000-stereo.opus", 48000, "stereo", ["-c:a", "libopus", "-b:a", "96k"]),
    ("flac-96000-24bit-stereo.flac", 96000, "stereo", ["-c:a", "flac", "-sample_fmt", "s32"]),
    ("flac-48000-16bit-5.1.flac", 48000, "5.1", ["-c:a", "flac", "-sample_fmt", "s16"]),
    ("wav-44100-16bit-stereo.wav", 44100, "stereo", ["-c:a", "pcm_s16le"]),
    ("wav-11025-16bit-mono.wav", 11025, "mono", ["-c:a", "pcm_s16le"]),
    ("wav-48000-24bit-stereo.wav", 48000, "stereo", ["-c:a", "pcm_s24le"]),
    ("wav-44100-float-mono.wav", 44100, "mono", ["-c:a", "pcm_f32le"]),
    ("flac-48000-24bit-stereo-big-frames.flac", 48000, "stereo",
     ["-c:a", "flac", "-sample_fmt", "s32", "-frame_size", "32768", "-f", "flac", "pipe"]),
]


def ffmpeg(arguments, **kwargs):
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", *arguments], check=True, **kwargs)


def encode(target, rate, layout, encoder, seconds=SECONDS, noise=False):
    """Writes the tone (with -30 dB of noise if [noise]) to [target], through standard output for "pipe" encoders."""
    expression = "|".join([f"0.5*sin(2*PI*{TONE_HZ}*t)"] * CHANNELS[layout])
    tone = f"aevalsrc=exprs='{expression}':channel_layout={layout}:sample_rate={rate}:duration={seconds}"
    inputs = ["-f", "lavfi", "-i", tone]
    graph = f"aformat=channel_layouts={layout}"
    if noise:
        inputs += ["-f", "lavfi",
                   "-i", f"anoisesrc=color=white:amplitude=0.03:sample_rate={rate}:duration={seconds}:seed=7"]
        graph = f"[0][1]amix=inputs=2:normalize=0,{graph}"
    command = [*inputs, "-filter_complex", graph, "-ar", str(rate), *encoder,
               "-map_metadata", "-1", "-fflags", "+bitexact", "-flags:a", "+bitexact"]
    if encoder[-1] == "pipe":
        command.remove("pipe")
        with open(target, "wb") as out:
            ffmpeg([*command, "-"], stdout=out)
    else:
        ffmpeg([*command, str(target)])


def clear_frame_sizes(path):
    """Sets STREAMINFO's minimum and maximum frame size to 0, which FLAC defines as "unknown"."""
    data = bytearray(path.read_bytes())
    if data[:4] != b"fLaC" or data[4] & 0x7F != 0:
        sys.exit(f"{path} does not start with a STREAMINFO block")
    data[12:18] = bytes(6)  # after the 4-byte block header and the two 16-bit block sizes
    path.write_bytes(data)


def edge_cases(scratch):
    # A FLAC file cut off in the middle of its last frame, as an interrupted copy leaves it.
    whole = scratch / "whole.flac"
    encode(whole, 44100, "mono", ["-c:a", "flac", "-sample_fmt", "s16"])
    (OUT / "flac-44100-16bit-mono-truncated.flac").write_bytes(whole.read_bytes()[:-300])

    # A FLAC file of a single frame: 50 ms is shorter than one block of 4096 samples.
    encode(OUT / "flac-44100-16bit-mono-one-frame.flac", 44100, "mono", ["-c:a", "flac", "-sample_fmt", "s16"],
           seconds=0.05)

    # An MP3 whose sample rate rises halfway: two files joined, without the Xing frame that would describe only the
    # first half.
    parts = []
    for rate in (44100, 48000):
        part = scratch / f"part-{rate}.mp3"
        encode(part, rate, "stereo", ["-c:a", "libmp3lame", "-b:a", "128k", "-write_xing", "0", "-id3v2_version", "0"],
               seconds=1)
        parts.append(part.read_bytes())
    (OUT / "mp3-44100-then-48000-stereo.mp3").write_bytes(b"".join(parts))

    # 64-bit floats: Media3 reads them, but Android has no PCM encoding for them.
    encode(OUT / "wav-44100-double-mono.wav", 44100, "mono", ["-c:a", "pcm_f64le"], seconds=0.1)


def main():
    if shutil.which("ffmpeg") is None:
        sys.exit("ffmpeg is not on PATH")
    OUT.mkdir(parents=True, exist_ok=True)
    for name, rate, layout, encoder in FIXTURES:
        piped = encoder[-1] == "pipe"
        encode(OUT / name, rate, layout, encoder, noise=piped)
        if piped:
            clear_frame_sizes(OUT / name)
    scratch = OUT / ".scratch"
    scratch.mkdir(exist_ok=True)
    try:
        edge_cases(scratch)
    finally:
        shutil.rmtree(scratch)
    for path in sorted(OUT.iterdir()):
        print(f"{path.name}: {path.stat().st_size} bytes")


if __name__ == "__main__":
    main()
