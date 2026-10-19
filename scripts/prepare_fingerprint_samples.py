"""Prepares the recordings of the Chromaprint instrumented tests.

For every sample in the manifest the script downloads the recording once into a cache outside the repository, checks
its size and hashes, decodes the first 120 seconds with ffmpeg into 11025 Hz mono 16-bit PCM and runs the desktop
fpcalc on that same PCM. The PCM and fpcalc's raw fingerprint become androidTest assets, so the device computes its
fingerprint from exactly the bytes fpcalc saw.

Usage: python scripts/prepare_fingerprint_samples.py --out <assets dir>
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
    partial = target.with_suffix(target.suffix + ".part")
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
    samples = json.loads(args.manifest.read_text(encoding="utf-8"))["samples"]
    args.cache.mkdir(parents=True, exist_ok=True)
    out = args.out / "fingerprints"
    out.mkdir(parents=True, exist_ok=True)

    index = []
    for sample in samples:
        print(sample["id"])
        source = fetch(sample, args.cache)
        if args.print_sha256:
            print(f"  sha256 {digest(source, 'sha256')}")
        pcm = out / f"{sample['id']}.pcm"
        subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(source), "-t", str(SECONDS),
                        "-ac", "1", "-ar", str(SAMPLE_RATE), "-c:a", "pcm_s16le", "-f", "s16le", str(pcm)], check=True)
        result = subprocess.run([str(args.fpcalc), "-format", "s16le", "-rate", str(SAMPLE_RATE), "-channels", "1",
                                 "-length", "0", "-raw", "-json", str(pcm)], check=True, capture_output=True, text=True)
        fingerprint = json.loads(result.stdout)
        (out / f"{sample['id']}.fpcalc.json").write_text(json.dumps(fingerprint), encoding="utf-8")
        index.append({"id": sample["id"], "pcmBytes": pcm.stat().st_size, "items": len(fingerprint["fingerprint"])})
        print(f"  {pcm.stat().st_size} bytes of PCM, {len(fingerprint['fingerprint'])} fingerprint items")
    (out / "index.json").write_text(json.dumps(index, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
