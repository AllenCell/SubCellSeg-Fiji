"""Bridge between the SubCellSeg Fiji plugin and the subcellseg Python package.

Called by the Java side as:
    python -u subcellseg_bridge.py --input <dir of frame_XXXX.tif> --output <dir>
                                   --device auto|gpu|cpu --threshold 0.5
                                   --outputs mask,prob [--checkpoint path]

For every ``<name>.tif`` in the input directory it writes ``<name>_prob.tif``
(uint8, 0-255 = probability 0-1) and/or ``<name>_mask.tif`` (uint8, 0/255) to
the output directory. Both hold the same values as the files written by
``subcellseg predict``.

Progress lines prefixed with ``SUBCELLSEG_STATUS`` go to the Fiji status bar;
an error summary is reported with the ``SUBCELLSEG_ERROR`` prefix.
"""
import argparse
import sys
import traceback
import warnings
from pathlib import Path


def status(msg):
    print("SUBCELLSEG_STATUS " + msg, flush=True)


def fail(msg, code=1):
    # One line, so that the Java side receives the whole message.
    print("SUBCELLSEG_ERROR " + " ".join(str(msg).split()), flush=True)
    sys.exit(code)


def parse_args():
    p = argparse.ArgumentParser(description="SubCellSeg Fiji bridge")
    p.add_argument("--input", required=True, help="Directory of input TIFFs (one ZYX volume each)")
    p.add_argument("--output", required=True, help="Directory for result TIFFs")
    p.add_argument("--checkpoint", default=None,
                   help="Weights file (.pt or .ckpt); default = the pretrained SubCellSeg weights")
    p.add_argument("--device", default="auto", choices=["auto", "gpu", "cpu"])
    p.add_argument("--threshold", type=float, default=0.5, help="Probability threshold in [0, 1]")
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

    in_dir = Path(args.input)
    out_dir = Path(args.output)
    out_dir.mkdir(parents=True, exist_ok=True)
    want = {s.strip() for s in args.outputs.split(",") if s.strip()}
    if not want or not want <= {"mask", "prob"}:
        fail("--outputs must be a subset of mask,prob")

    inputs = sorted(in_dir.glob("*.tif"))
    if not inputs:
        fail("No input TIFFs found in %s" % in_dir)

    status("Loading SubCellSeg...")
    import numpy as np
    import tifffile

    try:
        from subcellseg import SubCellSeg
        from subcellseg.inference import to_mask
        from subcellseg.weights import WEIGHTS_FILENAME, cache_dir
    except ImportError as e:  # surfaced to the user by the Java side
        fail("No module named subcellseg (%s)" % e)

    if args.checkpoint is None and not (cache_dir() / WEIGHTS_FILENAME).exists():
        status("Downloading the model (first run only, about 65 MB)...")
    model = SubCellSeg(checkpoint=args.checkpoint, device=args.device)
    where = "GPU" if model.device.type == "cuda" else "CPU (slow)"

    for i, f in enumerate(inputs, start=1):
        volume = to_zyx(tifffile.imread(f))
        z, y, x = volume.shape
        status("Segmenting frame %d of %d (%dx%dx%d ZYX) on the %s..." % (i, len(inputs), z, y, x, where))
        probability = model.predict_proba(volume)  # float32, 0-1

        if "prob" in want:
            prob8 = np.round(probability * 255).astype(np.uint8)
            tifffile.imwrite(out_dir / ("%s_prob.tif" % f.stem), prob8)
        if "mask" in want:
            mask = to_mask(probability, args.threshold)  # uint8, 0/255
            tifffile.imwrite(out_dir / ("%s_mask.tif" % f.stem), mask)
            status("Frame %d of %d: %d foreground voxels" % (i, len(inputs), int(np.count_nonzero(mask))))

    print("SUBCELLSEG_DONE", flush=True)


if __name__ == "__main__":
    try:
        main()
    except SystemExit:
        raise
    except Exception as exc:  # noqa: BLE001
        traceback.print_exc()
        fail("%s: %s" % (type(exc).__name__, exc))
