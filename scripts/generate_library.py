"""Generates the 20 000-file library for the library checks (docs/library.md §1.6, §1.7) and puts it on a device.

Every file has its own sound: two tones and pink noise seeded by the file's number, mono at 8 kHz, so the files tell
apart by content and stay small. Most last 31 s, 100 last 3 s. Formats: mostly MP3 at 8 kbps; 200 MP3 with a variable
bitrate and no frame-count header; 200 each of FLAC, Ogg Vorbis and M4A (AAC); 100 Opus.

The tree: about 400 made-up artists with five albums of ten tracks, in Latin with diacritics and in Cyrillic (with "ё"),
some with punctuation, leading articles and sort tags; multi-disc albums in CD1 and CD2 folders; a compilation; titles
shared by many artists, very long titles and titles with an emoji (in the tags only, so that file names stay short);
100 files without any tags; 50 files outside album folders. A few fixed files are what GeneratedLibraryTest looks for.
The same arguments always give the same files; the sound is synthetic, so the files carry no third-party rights.

Needs ffmpeg with libmp3lame, libvorbis and libopus, AtomicParsley (ffmpeg drops sort tags from M4A files), and adb
for --push. Run from the repository root:
    python scripts/generate_library.py                  # writes build/generated-library/PiPlayerTest20k.tar
    python scripts/generate_library.py --push           # also unpacks it to Music/PiPlayerTest20k/ on the device
    python scripts/generate_library.py --remove         # removes it from the device
adb picks the device as usual (ANDROID_SERIAL when several are connected).
"""
import argparse
import concurrent.futures
import os
import pathlib
import random
import subprocess
import sys
import tarfile

ROOT = "PiPlayerTest20k"
DEVICE_MUSIC = "/sdcard/Music"
DEVICE_TAR = "/data/local/tmp/PiPlayerTest20k.tar"
PACKAGE = "io.github.lorddetson.piplayer"
TOTAL = 20_000
RATE = 8000
LONG_SECONDS = 31
SHORT_SECONDS = 3
COMMON = ["-fflags", "+bitexact", "-flags:a", "+bitexact"]

# extension, encoder arguments, how the format names sort tags and the compilation flag
FORMATS = {
    "mp3": ("mp3", ["-c:a", "libmp3lame", "-b:a", "8k", "-id3v2_version", "4", "-write_id3v1", "0"]),
    "mp3-vbr": ("mp3", ["-c:a", "libmp3lame", "-q:a", "9", "-write_xing", "0", "-id3v2_version", "4",
                        "-write_id3v1", "0"]),
    "flac": ("flac", ["-c:a", "flac", "-sample_fmt", "s16"]),
    "ogg": ("ogg", ["-c:a", "libvorbis", "-q:a", "0"]),
    "opus": ("opus", ["-c:a", "libopus", "-b:a", "8k"]),
    "m4a": ("m4a", ["-c:a", "aac", "-b:a", "16k", "-f", "ipod"]),
}
SORT_KEYS = {
    "mp3": ("artist-sort", "album-sort"),
    "flac": ("ARTISTSORT", "ALBUMSORT"),
    "ogg": ("ARTISTSORT", "ALBUMSORT"),
    "opus": ("ARTISTSORT", "ALBUMSORT"),
    # Written by AtomicParsley afterwards: ffmpeg's MP4 muxer drops these keys.
    "m4a": ("artist", "album"),
}

LATIN = ["ka", "lo", "mi", "ra", "ne", "to", "su", "vi", "an", "el", "or", "is", "ul", "dan", "fel", "gor", "hen",
         "kel", "mar", "sol", "tin", "ver", "wal", "rö", "lé", "ná", "sü", "zoë", "çe", "ñu"]
CYRILLIC = ["ка", "ло", "ми", "ра", "не", "то", "су", "ви", "ан", "ел", "ор", "ис", "ул", "дан", "фел", "гор", "хен",
            "кел", "мар", "сол", "тин", "вер", "вал", "лё", "рё", "ня", "сю", "ёж", "щу", "жи"]
GENRES = ["Rock", "Pop", "Jazz", "Электроника", "Шансон", "Classical", "Hip-Hop", "Фолк", "Ambient", "Метал"]
PUNCTUATION_NAMES = ["R.E.N.", "Ka/Lo", "Rö & Sü", "Ёж-Ёж", "Mi'Ra", "2 Мира", "Lo-Fi Ка", "(Не)Ра"]


class Track:
    """One file to write: where it goes, its format, length and tags (None for none)."""

    def __init__(self, path, kind, seconds, tags):
        self.path, self.kind, self.seconds, self.tags, self.number = path, kind, seconds, tags, 0


def file_name(text):
    """A name Windows and Android both accept: no reserved characters, at most 60 characters."""
    cleaned = "".join("-" if c in '/\\:*?"<>|' else c for c in text).strip(" .")
    return cleaned[:60].rstrip(" .")


def word(rng, syllables):
    return "".join(rng.choice(syllables) for _ in range(rng.randint(2, 3))).capitalize()


def words(rng, syllables, low, high):
    return " ".join(word(rng, syllables) for _ in range(rng.randint(low, high)))


def fixed_tracks():
    """The files GeneratedLibraryTest checks, and the ones without tags or outside album folders."""
    tracks = [Track("Ёлкин Бор/Ночной ёж/01 Ёжик в тумане.mp3", "mp3", LONG_SECONDS,
                    {"title": "Ёжик в тумане", "artist": "Ёлкин Бор", "album": "Ночной ёж", "track": "1/1",
                     "date": "1975", "genre": "Фолк"})]
    suite = [("CD1", 1, "Overture"), ("CD1", 2, "Adagio"), ("CD2", 1, "Finale"), ("CD2", 2, "Coda")]
    for folder, number, title in suite:
        tracks.append(Track(f"Orchestra Nova/Grand Suite/{folder}/{number:02d} {title}.flac", "flac", LONG_SECONDS,
                            {"title": title, "artist": "Orchestra Nova", "album_artist": "Orchestra Nova",
                             "album": "Grand Suite", "track": f"{number}/2", "disc": f"{folder[-1]}/2",
                             "date": "2001", "genre": "Classical", "composer": "Nova Suite"}))
    compilation = ["Rö & Sü", "Ёлкин Бор", "Zoë Lune", "Mi'Ra", "Орбита", "The Kelvins", "Ka/Lo", "Сюита", "Lé Na",
                   "Дан Фел"]
    titles = ["Overture", "Зимний вечер", "Lune de miel", "Intro", "Ёлочка", "Outro", "Ночь", "Song", "Café", "Дождь"]
    for number, (artist, title) in enumerate(zip(compilation, titles), start=1):
        tracks.append(Track(f"Various Artists/Сборник 2026/{number:02d} {file_name(title)}.m4a", "m4a", LONG_SECONDS,
                            {"title": title, "artist": artist, "album_artist": "Various Artists",
                             "album": "Сборник 2026", "track": f"{number}/10", "date": "2026", "compilation": "1"}))
    for number in range(1, 101):
        tracks.append(Track(f"Без тегов/track {number:04d}.mp3", "mp3", LONG_SECONDS, None))
    for number in range(1, 51):
        tracks.append(Track(f"Loose track {number:02d}.mp3", "mp3", LONG_SECONDS,
                            {"title": f"Loose track {number:02d}", "artist": "Loose Artist"}))
    return tracks


def generated_tracks(rng, count):
    """The rest of the library: artists with five albums of ten tracks until [count] files."""
    tracks = []
    artist_number = 0
    while len(tracks) < count:
        artist_number += 1
        syllables = LATIN if rng.random() < 0.5 else CYRILLIC
        if artist_number % 40 == 0:
            artist = PUNCTUATION_NAMES[(artist_number // 40) % len(PUNCTUATION_NAMES)] + f" {artist_number}"
        else:
            artist = words(rng, syllables, 1, 2)
        artist_sort = None
        if artist_number % 8 == 0:
            artist_sort = f"{artist}, The"
            artist = f"The {artist}"
        for album_number in range(1, 6):
            album = words(rng, syllables, 1, 3)
            album_sort = f"{album} (sort)" if rng.random() < 0.1 else None
            discs = 2 if rng.random() < 0.1 else 1
            year = rng.randint(1960, 2026)
            genre = rng.choice(GENRES)
            for track_number in range(1, 11):
                if len(tracks) >= count:
                    break
                index = len(tracks)
                if index % 50 == 7:
                    title = "Intro"
                else:
                    title = words(rng, syllables, 1, 4)
                if index % 500 == 11:
                    title = " ".join([title] * (200 // (len(title) + 1) + 1))[:200]
                if index % 1000 == 13:
                    title += " \U0001F3B5"
                kind = {0: "flac", 1: "ogg", 2: "m4a", 3: "mp3-vbr"}.get(index % 100, "mp3")
                if index % 200 == 4:
                    kind = "opus"
                seconds = SHORT_SECONDS if index % 200 == 5 else LONG_SECONDS
                disc, number = (1, track_number) if discs == 1 else ((track_number - 1) // 5 + 1, (track_number - 1) % 5 + 1)
                folder = f"{file_name(artist)}/{file_name(album)}"
                if discs == 2:
                    folder += f"/CD{disc}"
                extension = FORMATS[kind][0]
                tags = {"title": title, "artist": artist, "album": album, "album_artist": artist,
                        "track": f"{number}/{10 // discs}", "date": str(year), "genre": genre}
                if discs == 2:
                    tags["disc"] = f"{disc}/2"
                if rng.random() < 0.3:
                    tags["composer"] = words(rng, syllables, 2, 2)
                if artist_sort:
                    tags["artist_sort"] = artist_sort
                if album_sort:
                    tags["album_sort"] = album_sort
                path = f"{folder}/{number:02d} {file_name(title)}.{extension}"
                tracks.append(Track(path, kind, seconds, tags))
    return tracks


def metadata_arguments(track):
    if track.tags is None:
        if track.kind.startswith("mp3"):
            return ["-map_metadata", "-1", "-id3v2_version", "0"]
        return ["-map_metadata", "-1"]
    family = "mp3" if track.kind.startswith("mp3") else track.kind
    arguments = ["-map_metadata", "-1"]
    for key, value in track.tags.items():
        if family == "m4a" and key in ("artist_sort", "album_sort"):
            continue
        if key == "artist_sort":
            key = SORT_KEYS[family][0]
        elif key == "album_sort":
            key = SORT_KEYS[family][1]
        elif key == "compilation" and family in ("flac", "ogg", "opus"):
            key = "COMPILATION"
        arguments += ["-metadata", f"{key}={value}"]
    return arguments


def encode(track, out_dir):
    target = out_dir / ROOT / track.path
    if target.exists():
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    n = track.number
    tone1 = 110 + (n * 7) % 2900
    tone2 = 3600 - (n * 37) % 3000
    sources = [
        "-f", "lavfi", "-i", f"sine=frequency={tone1}:sample_rate={RATE}:duration={track.seconds}",
        "-f", "lavfi", "-i", f"sine=frequency={tone2}:sample_rate={RATE}:duration={track.seconds}",
        "-f", "lavfi", "-i", f"anoisesrc=duration={track.seconds}:color=pink:sample_rate={RATE}:amplitude=0.05:seed={n + 1}",
    ]
    mix = ["-filter_complex", "[0][1][2]amix=inputs=3:normalize=0,volume=0.4", "-ac", "1", "-ar", str(RATE)]
    # The extension stays last, so that AtomicParsley accepts the file.
    partial = target.with_name(target.stem + ".part" + target.suffix)
    command = (["ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y"] + sources + mix
               + FORMATS[track.kind][1] + metadata_arguments(track) + COMMON)
    if "-f" not in FORMATS[track.kind][1]:
        command += ["-f", {"mp3": "mp3", "flac": "flac", "ogg": "ogg", "opus": "ogg"}[FORMATS[track.kind][0]]]
    # Windows sometimes fails to start a process under load (0xC0000142); a second try succeeds.
    for attempt in range(3):
        try:
            subprocess.run(command + [str(partial)], check=True)
            break
        except subprocess.CalledProcessError:
            if attempt == 2:
                raise
    if track.kind == "m4a" and track.tags:
        sorts = [(kind, track.tags[key]) for key, kind in (("artist_sort", "artist"), ("album_sort", "album"))
                 if key in track.tags]
        if sorts:
            command = ["AtomicParsley", str(partial), "--overWrite"]
            for kind, value in sorts:
                command += ["--sortOrder", kind, value]
            subprocess.run(command, check=True, stdout=subprocess.DEVNULL)
    partial.replace(target)


def build(out_dir, jobs, seed):
    rng = random.Random(seed)
    fixed = fixed_tracks()
    tracks = fixed + generated_tracks(rng, TOTAL - len(fixed))
    assert len(tracks) == TOTAL
    # The number every file's sound comes from: one per file, so no two files sound the same.
    for number, track in enumerate(tracks, start=1):
        track.number = number
    paths = [track.path for track in tracks]
    assert len(set(paths)) == len(paths), "two files with one path"
    with concurrent.futures.ThreadPoolExecutor(jobs) as pool:
        for done, _ in enumerate(pool.map(lambda t: encode(t, out_dir), tracks), start=1):
            if done % 1000 == 0:
                print(f"{done} / {TOTAL}", flush=True)
    archive = out_dir / f"{ROOT}.tar"
    with tarfile.open(archive, "w", format=tarfile.GNU_FORMAT, encoding="utf-8") as tar:
        for path in sorted(paths):
            tar.add(out_dir / ROOT / path, arcname=f"{ROOT}/{path}", recursive=False)
    size = sum((out_dir / ROOT / p).stat().st_size for p in paths)
    print(f"{TOTAL} files, {size / 1e6:.0f} MB, in {archive}")
    return archive


def adb(*arguments):
    subprocess.run(["adb", *arguments], check=True)


def scan():
    """Asks the media index to scan the folder, so that it lists the files without a reboot."""
    adb("shell", "content", "call", "--uri", "content://media", "--method", "scan_volume", "--arg", "external_primary")


def push(archive):
    # A running PiPlayer syncs its library after every burst of index changes, which makes unpacking several times slower.
    adb("shell", "am", "force-stop", PACKAGE)
    adb("push", str(archive), DEVICE_TAR)
    adb("shell", f"mkdir -p {DEVICE_MUSIC} && cd {DEVICE_MUSIC} && rm -rf {ROOT} && tar -xf {DEVICE_TAR} && rm {DEVICE_TAR}")
    scan()


def remove():
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", f"rm -rf {DEVICE_MUSIC}/{ROOT}")
    scan()


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", type=pathlib.Path, default=pathlib.Path("build/generated-library"))
    parser.add_argument("--jobs", type=int, default=min(8, os.cpu_count() or 4))
    parser.add_argument("--seed", type=int, default=2026)
    parser.add_argument("--push", action="store_true", help="unpack the library on the device")
    parser.add_argument("--remove", action="store_true", help="remove the library from the device")
    args = parser.parse_args()
    if args.remove:
        remove()
        return
    archive = build(args.out, args.jobs, args.seed)
    if args.push:
        push(archive)


if __name__ == "__main__":
    sys.exit(main())
