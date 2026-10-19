"""Generates the tag fixtures of the TagLib instrumented tests.

Each fixture is a one-second tone with known tags, in every format PiPlayer reads tags from. The files are made by
ffmpeg from synthetic sound, so they carry no third-party rights. The expected values live in the tests
(nativelib/src/androidTest/.../TagFixtures.kt); keep the two in step.

Run from the repository root: python scripts/generate_tag_fixtures.py
"""
import pathlib
import shutil
import subprocess
import sys
import tempfile

OUT = pathlib.Path("nativelib/src/androidTest/assets/tags")

LATIN = {
    "title": "Paper Lanterns",
    "artist": "The Quiet Harbors",
    "album": "Harbor Lights",
    "date": "2019",
    "track": "3/11",
    "genre": "Ambient",
}
CYRILLIC = {
    "title": "Бумажные фонари",
    "artist": "Тихая Гавань",
    "album": "Огни гавани",
    "date": "2019",
    "track": "3/11",
    "genre": "Эмбиент",
}

# name, encoder arguments, tags, embedded front cover
FIXTURES = [
    ("id3v23.mp3", ["-c:a", "libmp3lame", "-b:a", "64k", "-id3v2_version", "3"], LATIN, True),
    ("id3v24.mp3", ["-c:a", "libmp3lame", "-b:a", "64k", "-id3v2_version", "4"], LATIN, True),
    # ffmpeg writes ID3v1 only together with ID3v2; strip_id3v2() removes the latter afterwards.
    ("id3v1.mp3", ["-c:a", "libmp3lame", "-b:a", "64k", "-id3v2_version", "3", "-write_id3v1", "1"], LATIN, False),
    ("vorbis-comment.flac", ["-c:a", "flac"], LATIN, True),
    ("aac.m4a", ["-c:a", "aac", "-b:a", "64k"], LATIN, True),
    ("alac.m4a", ["-c:a", "alac"], LATIN, False),
    ("vorbis.ogg", ["-c:a", "libvorbis", "-q:a", "1"], LATIN, False),
    ("opus.opus", ["-c:a", "libopus", "-b:a", "32k"], LATIN, False),
    ("riff-info.wav", ["-c:a", "pcm_s16le"], LATIN, False),
    ("cyrillic.flac", ["-c:a", "flac"], CYRILLIC, False),
    ("cyrillic-id3v23.mp3", ["-c:a", "libmp3lame", "-b:a", "64k", "-id3v2_version", "3"], CYRILLIC, False),
]


def run(arguments):
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", *arguments], check=True)


def strip_id3v2(path):
    """Removes a leading ID3v2 tag: 'ID3', version, flags, a synchsafe size, and a footer when flag bit 4 is set."""
    data = path.read_bytes()
    if data[:3] != b"ID3":
        return
    size = (data[6] << 21) | (data[7] << 14) | (data[8] << 7) | data[9]
    end = 10 + size + (10 if data[5] & 0x10 else 0)
    path.write_bytes(data[end:])


def main():
    if shutil.which("ffmpeg") is None:
        sys.exit("ffmpeg is not on PATH")
    OUT.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as work:
        cover = pathlib.Path(work, "cover.png")
        run(["-f", "lavfi", "-i", "color=c=0x3D5AFE:s=64x64", "-frames:v", "1", str(cover)])
        for name, encoder, tags, with_cover in FIXTURES:
            inputs = ["-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100:duration=1"]
            maps = ["-map", "0:a"]
            if with_cover:
                inputs += ["-i", str(cover)]
                maps += ["-map", "1:v", "-c:v", "copy", "-disposition:v", "attached_pic",
                         "-metadata:s:v", "comment=Cover (front)"]
            metadata = [argument for key, value in tags.items() for argument in ("-metadata", f"{key}={value}")]
            run([*inputs, *maps, "-ac", "2", *encoder, "-map_metadata", "-1", *metadata,
                 "-fflags", "+bitexact", "-flags:a", "+bitexact", str(OUT / name)])
            if name == "id3v1.mp3":
                strip_id3v2(OUT / name)
            print(f"{name}: {(OUT / name).stat().st_size} bytes")


if __name__ == "__main__":
    main()
