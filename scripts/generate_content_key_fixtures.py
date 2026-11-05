"""Generates the fixtures of the content key tests into app/src/androidTest/assets/contentkey.

Each file is synthetic sound from ffmpeg, so it carries no third-party rights. The payloads are longer than 128 KB, so
the keys take their first and last 64 KB. The variants are the same audio with other tags, rewritten by real tools
(ffmpeg) or by this script (Ogg comments, because ffmpeg pages the audio anew).

Run from the repository root: python scripts/generate_content_key_fixtures.py
"""
import base64
import os
import pathlib
import random
import shutil
import subprocess
import sys

OUT = pathlib.Path("app/src/androidTest/assets/contentkey")
NOISE = ["-f", "lavfi", "-i", "anoisesrc=color=pink:seed=7:amplitude=0.4:sample_rate=48000:duration=20"]
SECOND_NOISE = ["-f", "lavfi", "-i", "anoisesrc=color=white:seed=9:amplitude=0.2:sample_rate=48000:duration=3"]


def ffmpeg(*arguments):
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", *arguments], check=True)


def crc(page):
    value = 0
    for byte in page:
        value ^= byte << 24
        for _ in range(8):
            value = ((value << 1) ^ 0x04C11DB7) & 0xFFFFFFFF if value & 0x80000000 else (value << 1) & 0xFFFFFFFF
    return value


def split_pages(data):
    pages = []
    at = 0
    while at < len(data):
        if data[at:at + 4] != b"OggS":
            sys.exit(f"no page at {at}")
        count = data[at + 26]
        lacing = data[at + 27:at + 27 + count]
        end = at + 27 + count + sum(lacing)
        pages.append(bytearray(data[at:end]))
        at = end
    return pages


def header_packets(pages, needed):
    """The first [needed] packets and the index of the first page after them."""
    packets = []
    current = bytearray()
    for index, page in enumerate(pages):
        count = page[26]
        lacing = page[27:27 + count]
        at = 27 + count
        for value in lacing:
            current += page[at:at + value]
            at += value
            if value < 255:
                packets.append(bytes(current))
                current = bytearray()
                if len(packets) == needed:
                    return packets, index + 1
    sys.exit("too few header packets")


def page_of(serial, sequence, flags, lacing, body):
    page = bytearray(b"OggS\0" + bytes([flags]) + bytes(8) + serial + sequence.to_bytes(4, "little") + bytes(4))
    page += bytes([len(lacing)]) + bytes(lacing) + body
    page[22:26] = crc(page).to_bytes(4, "little")
    return page


def page_packets(packets, serial, sequence, per_page):
    """Pages holding [packets], at most [per_page] lacing values each."""
    values = []
    body = b"".join(packets)
    for packet in packets:
        values += [255] * (len(packet) // 255) + [len(packet) % 255]
    pages = []
    at = 0
    flags = 0
    while values:
        lacing, values = values[:per_page], values[per_page:]
        size = sum(lacing)
        pages.append(page_of(serial, sequence, flags, lacing, body[at:at + size]))
        sequence += 1
        at += size
        flags = 1 if lacing[-1] == 255 else 0
    return pages, sequence


def rewrite_comments(source, target, comments, needed, prefix, per_page=255):
    """Replaces the comment packet of an Ogg file and pages the headers anew; the audio pages keep their bodies."""
    pages = split_pages(source.read_bytes())
    packets, audio_from = header_packets(pages, needed)
    serial = bytes(pages[0][14:18])
    vendor = b"PiPlayer fixture"
    body = len(vendor).to_bytes(4, "little") + vendor + len(comments).to_bytes(4, "little")
    for comment in comments:
        body += len(comment).to_bytes(4, "little") + comment
    comment_packet = prefix + body + (b"\x01" if needed == 3 else b"")
    rest, sequence = page_packets([comment_packet, *packets[2:]], serial, 1, per_page)
    output = bytearray(pages[0]) + b"".join(rest)
    for page in pages[audio_from:]:
        page = bytearray(page)
        page[18:22] = sequence.to_bytes(4, "little")
        sequence += 1
        page[22:26] = bytes(4)
        page[22:26] = crc(page).to_bytes(4, "little")
        output += page
    target.write_bytes(output)


def picture_comment(size):
    picture = random.Random(5).randbytes(size)
    mime = b"image/png"
    block = (3).to_bytes(4, "big") + len(mime).to_bytes(4, "big") + mime + bytes(4) + bytes(16)
    block += len(picture).to_bytes(4, "big") + picture
    return b"METADATA_BLOCK_PICTURE=" + base64.b64encode(block)


def main():
    if shutil.which("ffmpeg") is None:
        sys.exit("ffmpeg is not on PATH")
    OUT.mkdir(parents=True, exist_ok=True)

    ffmpeg(*NOISE, "-c:a", "libmp3lame", "-q:a", "6", "-map_metadata", "-1", "-id3v2_version", "0",
           "-fflags", "+bitexact", str(OUT / "long.mp3"))
    # A copy gets a new Xing frame and an ID3v2 tag: the same audio frames.
    ffmpeg("-i", str(OUT / "long.mp3"), "-c", "copy", "-metadata", "title=Paper Lanterns", "-id3v2_version", "3",
           "-write_id3v1", "1", str(OUT / "long-recopied.mp3"))

    ffmpeg(*NOISE, "-c:a", "aac", "-b:a", "96k", "-map_metadata", "-1", "-fflags", "+bitexact",
           str(OUT / "moov-last.m4a"))
    ffmpeg("-i", str(OUT / "moov-last.m4a"), "-c", "copy", "-movflags", "+faststart", "-metadata",
           "title=Paper Lanterns", str(OUT / "moov-first.m4a"))

    ffmpeg(*NOISE, "-t", "3", "-c:a", "flac", "-map_metadata", "-1", "-fflags", "+bitexact", str(OUT / "long.flac"))

    ffmpeg(*NOISE, "-c:a", "libvorbis", "-q:a", "2", "-map_metadata", "-1", "-fflags", "+bitexact",
           "-serial_offset", "1000", str(OUT / "long.ogg"))
    ffmpeg(*NOISE, "-c:a", "libopus", "-b:a", "96k", "-map_metadata", "-1", "-fflags", "+bitexact",
           "-serial_offset", "2000", str(OUT / "long.opus"))
    ffmpeg(*SECOND_NOISE, "-c:a", "libvorbis", "-q:a", "2", "-map_metadata", "-1", "-fflags", "+bitexact",
           "-serial_offset", "3000", str(OUT / "second-link.ogg"))

    title = [b"TITLE=Paper Lanterns"]
    long_text = [b"TITLE=Paper Lanterns", b"COMMENT=" + b"x" * 90_000]
    cover = [b"TITLE=Paper Lanterns", picture_comment(250_000)]
    for name, needed, prefix in (("long.ogg", 3, b"\x03vorbis"), ("long.opus", 2, b"OpusTags")):
        stem, suffix = name.split(".")
        source = OUT / name
        rewrite_comments(source, OUT / f"{stem}-comments-short.{suffix}", title, needed, prefix)
        # Three lacing values per page: many more header pages than the original.
        rewrite_comments(source, OUT / f"{stem}-comments-small-pages.{suffix}", title, needed, prefix, per_page=3)
        rewrite_comments(source, OUT / f"{stem}-comments-long.{suffix}", long_text, needed, prefix)
        rewrite_comments(source, OUT / f"{stem}-cover.{suffix}", cover, needed, prefix)

    (OUT / "chained.ogg").write_bytes((OUT / "long.ogg").read_bytes() + (OUT / "second-link.ogg").read_bytes())
    os.remove(OUT / "second-link.ogg")


if __name__ == "__main__":
    main()
