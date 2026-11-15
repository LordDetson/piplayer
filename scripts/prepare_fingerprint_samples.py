"""Prepares the recordings of the fingerprint instrumented tests.

For every sample in the manifest the script downloads the recording once into a cache outside the repository and
checks its size and hashes. Then, by mode:

- "pcm" (nativelib): decodes the first 120 seconds with ffmpeg into 11025 Hz mono 16-bit PCM and runs the desktop
  fpcalc on that same PCM. The PCM and fpcalc's raw fingerprint become androidTest assets, so the device computes its
  fingerprint from exactly the bytes fpcalc saw.
- "files" (app): copies the recordings themselves and runs fpcalc on each file, which decodes and resamples it with
  FFmpeg. The device decodes the same file with its own decoders and resampler, and the test compares the two.

A sample with "derivedFrom" is not downloaded: ffmpeg makes it from the named recording with the sample's "ffmpeg"
arguments, in mode "files" only (in mode "pcm" it would only repeat its source). It covers what no freely licensed
recording offers (TI §3.2): 5.1 audio, AAC, an MP3 without a LAME or Xing header. It is the same recording at its full
length, so it inherits the source's credits, licence and IDs, and fpcalc and the device read the same derived file, so
an ffmpeg of another version only makes another reference.

Usage: python scripts/prepare_fingerprint_samples.py --out <assets dir> [--mode pcm|files]
       [--manifest nativelib/fingerprint-samples.json] [--cache <dir>] [--fpcalc <path>] [--print-sha256]
Needs ffmpeg on PATH and fpcalc 1.6.2 (FPCALC environment variable, --fpcalc, or the default under tools/).
"""
import argparse
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import sys
import time
import urllib.request

USER_AGENT = "PiPlayer-tests/0.1 (https://github.com/LordDetson/piplayer) python-urllib"
SAMPLE_RATE = 11025
SECONDS = 120
DEFAULT_FPCALC = pathlib.Path("tools/fpcalc-1.6.2/chromaprint-fpcalc-1.6.2-windows-x86_64/fpcalc.exe")
# What a derived sample takes over from its source; the download details and the format stay the source's own.
INHERITED = ["title", "performer", "composer", "license", "licenseUrl", "pageUrl", "musicbrainzRecording",
             "acoustidTrack"]


def digest(path, algorithm):
    hasher = hashlib.new(algorithm)
    with open(path, "rb") as file:
        for block in iter(lambda: file.read(1 << 20), b""):
            hasher.update(block)
    return hasher.hexdigest()


def verified(path, sample):
    """Size, the source's SHA-1 and, once pinned, our SHA-256 must all match."""
    if not path.exists() or path.stat().st_size != sample["size"]:
        return False
    if digest(path, "sha1") != sample["sha1"]:
        return False
    return sample.get("sha256") is None or digest(path, "sha256") == sample["sha256"]


def download(sample, target):
    request = urllib.request.Request(sample["url"], headers={"User-Agent": USER_AGENT})
    # Two Gradle tasks may download into the same cache at once; each writes its own partial file and renames it.
    partial = target.with_suffix(f"{target.suffix}.{os.getpid()}.part")
    for attempt in range(1, 4):
        try:
            with urllib.request.urlopen(request, timeout=60) as response, open(partial, "wb") as out:
                shutil.copyfileobj(response, out, 1 << 20)
            partial.replace(target)
            return
        except OSError as error:
            print(f"  download attempt {attempt} failed: {error}", file=sys.stderr)
            time.sleep(5 * attempt)
    sys.exit(f"Could not download {sample['url']}")


def fetch(sample, cache):
    name = sample["id"] + pathlib.Path(urllib.request.url2pathname(sample["url"].rsplit("/", 1)[1])).suffix
    path = cache / name
    if not verified(path, sample):
        print(f"  downloading {sample['url']}")
        download(sample, path)
        if not verified(path, sample):
            sys.exit(f"{path} does not match the size and hashes in the manifest; the source may have a new version")
    return path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", default="nativelib/fingerprint-samples.json", type=pathlib.Path)
    parser.add_argument("--out", required=True, type=pathlib.Path)
    parser.add_argument("--mode", choices=["pcm", "files"], default="pcm")
    parser.add_argument("--cache", type=pathlib.Path,
                        default=pathlib.Path(os.environ.get("PIPLAYER_SAMPLE_CACHE",
                                                            pathlib.Path.home() / ".cache/piplayer/fingerprint-samples")))
    parser.add_argument("--fpcalc", type=pathlib.Path,
                        default=pathlib.Path(os.environ.get("FPCALC", DEFAULT_FPCALC)))
    parser.add_argument("--print-sha256", action="store_true", help="print the SHA-256 of each cached recording")
    args = parser.parse_args()

    if shutil.which("ffmpeg") is None:
        sys.exit("ffmpeg is not on PATH")
    if not args.fpcalc.exists():
        sys.exit(f"fpcalc not found at {args.fpcalc}; set FPCALC or pass --fpcalc")
    samples = resolve(json.loads(args.manifest.read_text(encoding="utf-8"))["samples"])
    args.cache.mkdir(parents=True, exist_ok=True)
    out = args.out / ("fingerprints" if args.mode == "pcm" else "recordings")
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)

    index = []
    for sample in samples:
        if "derivedFrom" in sample and args.mode == "pcm":
            continue
        print(sample["id"])
        if "derivedFrom" in sample:
            source = derive(sample, fetch(sample["source"], args.cache), out)
        else:
            source = fetch(sample, args.cache)
            if args.print_sha256:
                print(f"  sha256 {digest(source, 'sha256')}")
        if args.mode == "pcm":
            index.append(prepare_pcm(sample, source, out, args.fpcalc))
        else:
            index.append(prepare_file(sample, source, out, args.fpcalc))
    (out / "index.json").write_text(json.dumps(index, indent=1), encoding="utf-8")


def resolve(samples):
    """Gives each derived sample its source's entry and the details it inherits from it."""
    by_id = {sample["id"]: sample for sample in samples}
    resolved = []
    for sample in samples:
        if "derivedFrom" in sample:
            source = by_id.get(sample["derivedFrom"])
            if source is None or "derivedFrom" in source:
                sys.exit(f"{sample['id']}: derivedFrom must name a downloaded sample")
            sample = {**{key: source[key] for key in INHERITED if key in source}, **sample, "source": source}
        resolved.append(sample)
    return resolved


def derive(sample, source, out):
    """Makes the derived recording from its source's audio, without the source's tags and pictures."""
    target = out / sample["file"]
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(source), "-map", "0:a:0",
                    *sample["ffmpeg"], "-map_metadata", "-1", "-fflags", "+bitexact", "-flags:a", "+bitexact",
                    str(target)], check=True)
    return target


def fpcalc_raw(fpcalc, path, *options):
    result = subprocess.run([str(fpcalc), *options, "-raw", "-json", str(path)],
                            check=True, capture_output=True, text=True)
    return json.loads(result.stdout)


def prepare_pcm(sample, source, out, fpcalc):
    pcm = out / f"{sample['id']}.pcm"
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(source), "-t", str(SECONDS),
                    "-ac", "1", "-ar", str(SAMPLE_RATE), "-c:a", "pcm_s16le", "-f", "s16le", str(pcm)], check=True)
    fingerprint = fpcalc_raw(fpcalc, pcm, "-format", "s16le", "-rate", str(SAMPLE_RATE), "-channels", "1",
                             "-length", "0")
    (out / f"{sample['id']}.fpcalc.json").write_text(json.dumps(fingerprint), encoding="utf-8")
    print(f"  {pcm.stat().st_size} bytes of PCM, {len(fingerprint['fingerprint'])} fingerprint items")
    return {"id": sample["id"], "pcmBytes": pcm.stat().st_size, "items": len(fingerprint["fingerprint"])}


def prepare_file(sample, source, out, fpcalc):
    target = out / source.name
    if source != target:  # a derived recording is made in place
        shutil.copyfile(source, target)
    fingerprint = fpcalc_raw(fpcalc, target, "-length", str(SECONDS))
    (out / f"{sample['id']}.fpcalc.json").write_text(json.dumps(fingerprint), encoding="utf-8")
    print(f"  {target.stat().st_size} bytes, {len(fingerprint['fingerprint'])} fingerprint items")
    return {"id": sample["id"], "file": target.name, "items": len(fingerprint["fingerprint"]),
            "acoustidTrack": sample["acoustidTrack"], "musicbrainzRecording": sample["musicbrainzRecording"]}


if __name__ == "__main__":
    main()
