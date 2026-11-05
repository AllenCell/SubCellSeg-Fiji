"""Bridge between the MegaSeg Fiji plugin and cytodl-mini.

Called by the Java side as:
    python -u megaseg_bridge.py --input <dir of frame_XXXX.tif> --output <dir>
                                --device auto|gpu|cpu --threshold 0.5 --min-size 0
                                --outputs mask,prob [--checkpoint path.ckpt]

For every ``<name>.tif`` in the input directory it writes ``<name>_prob.tif``
(uint8, 0-255) and/or ``<name>_mask.tif`` (uint8, 0/255) to the output directory.

Progress lines prefixed with ``MEGASEG_STATUS`` go to the Fiji status bar;
an error summary is reported with the ``MEGASEG_ERROR`` prefix.
"""
import argparse
import sys
import traceback
import warnings
from pathlib import Path


def status(msg):
    print("MEGASEG_STATUS " + msg, flush=True)


def fail(msg, code=1):
    print("MEGASEG_ERROR " + msg, flush=True)
    sys.exit(code)


def parse_args():
    p = argparse.ArgumentParser(description="MegaSeg Fiji bridge")
    p.add_argument("--input", required=True, help="Directory of input TIFFs (one ZYX volume each)")
    p.add_argument("--output", required=True, help="Directory for result TIFFs")
    p.add_argument("--checkpoint", default=None, help="MegaSeg checkpoint (.ckpt); default = cytodl-mini cache")
    p.add_argument("--device", default="auto", choices=["auto", "gpu", "cpu"])
    p.add_argument("--threshold", type=float, default=0.5, help="Probability threshold in [0, 1]")
    p.add_argument("--min-size", type=int, default=0, help="Remove 3D components smaller than this (voxels)")
    p.add_argument("--outputs", default="mask", help="Comma-separated subset of: mask,prob")
    return p.parse_args()


def to_zyx(arr):
    """Squeeze any (T/C/S)ZYX array down to a 3D ZYX array."""
    import numpy as np

    arr = np.squeeze(arr)
    if arr.ndim == 2:
        arr = arr[None, ...]
    while arr.ndim > 3:
        arr = arr[0]
    return arr


def main():
    args = parse_args()
    warnings.filterwarnings("ignore")

    status("Loading cytodl-mini...")
    import numpy as np
    import tifffile

    try:
        from cytodl_mini import predict
    except ImportError as e:  # surfaced to the user by the Java side
        fail("No module named cytodl_mini (%s)" % e)

    in_dir = Path(args.input)
    out_dir = Path(args.output)
    out_dir.mkdir(parents=True, exist_ok=True)
    want = {s.strip() for s in args.outputs.split(",") if s.strip()}
    if not want <= {"mask", "prob"}:
        fail("--outputs must be a subset of mask,prob")

    inputs = sorted(in_dir.glob("*.tif"))
    if not inputs:
        fail("No input TIFFs found in %s" % in_dir)

    # Re-save the ImageJ TIFFs as plain ZYX volumes so bioio reads them unambiguously.
    work = out_dir / "_work"
    clean_dir = work / "in"
    pred_dir = work / "pred"
    clean_dir.mkdir(parents=True, exist_ok=True)
    pred_dir.mkdir(parents=True, exist_ok=True)

    for f in inputs:
        img = to_zyx(tifffile.imread(f))
        if img.dtype not in (np.uint8, np.uint16):
            img = img.astype(np.float32)
        tifffile.imwrite(clean_dir / f.name, img, metadata={"axes": "ZYX"})
    z, y, x = img.shape
    status("Running MegaSeg on %d volume(s) of %dx%dx%d (ZYX), device=%s..." % (len(inputs), z, y, x, args.device))

    saved = predict(
        input_path=clean_dir,
        output_dir=pred_dir,
        model_name="megaseg",
        checkpoint=args.checkpoint,
        device=args.device,
    )
    saved = [Path(s) for s in saved]
    if not saved:
        saved = sorted(pred_dir.rglob("*.tif"))

    status("Writing results...")
    thr = int(round(float(args.threshold) * 255.0))
    thr = max(1, min(255, thr))
    for f in inputs:
        stem = f.stem
        matches = [s for s in saved if stem in s.name]
        if len(matches) != 1:
            fail("Could not match prediction for %s (found %d candidate files in %s)" % (f.name, len(matches), pred_dir))
        prob = to_zyx(tifffile.imread(matches[0]))
        if prob.dtype != np.uint8:
            # Defensive: normalise anything else to 0-255.
            prob = prob.astype(np.float32)
            if prob.max() <= 1.0:
                prob = prob * 255.0
            prob = np.clip(prob, 0, 255).astype(np.uint8)

        if "prob" in want:
            tifffile.imwrite(out_dir / ("%s_prob.tif" % stem), prob)
        if "mask" in want:
            mask = prob >= thr
            if args.min_size > 0:
                from skimage.morphology import remove_small_objects

                mask = remove_small_objects(mask, min_size=args.min_size)
            tifffile.imwrite(out_dir / ("%s_mask.tif" % stem), (mask.astype(np.uint8) * 255))
            status("%s: %d foreground voxels" % (stem, int(mask.sum())))

    print("MEGASEG_DONE", flush=True)


if __name__ == "__main__":
    try:
        main()
    except SystemExit:
        raise
    except Exception as exc:  # noqa: BLE001
        traceback.print_exc()
        fail("%s: %s" % (type(exc).__name__, exc))
