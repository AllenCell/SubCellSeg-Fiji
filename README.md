# MegaSeg for Fiji

One-click [MegaSeg](https://github.com/AllenCell/CytoDL-Mini) segmentation of 3D fluorescence
volumes from inside Fiji. Open an image, choose `Plugins › Segmentation › MegaSeg...`, press OK.

MegaSeg is a single generalist model: there is no structure to pick, no diameter to tune.
The plugin sends the selected channel to a Python process (cytodl-mini) and returns a binary mask
and/or an 8-bit probability map as new, calibrated images. Time-lapses are processed frame by frame.

![dialog](docs/dialog.png)

## Install

1. **Python side** (once). Create an environment with cytodl-mini and PyTorch:

   ```bash
   conda create -n cytodl-mini python=3.12
   conda activate cytodl-mini
   pip install torch --index-url https://download.pytorch.org/whl/cu124   # or cpu / your CUDA
   pip install git+https://github.com/AllenCell/CytoDL-Mini.git
   ```

   The MegaSeg checkpoint (~200 MB) is downloaded automatically on the first run into
   `~/.cache/cytodl-mini/`. While the CytoDL-Mini repository is internal this needs the GitHub CLI
   (`gh auth login`); alternatively download `megaseg.ckpt` once and point *Model checkpoint* at it.

2. **Fiji side**. Copy `MegaSeg_Fiji-<version>.jar` into `Fiji.app/plugins/` and restart Fiji.
   The jar is Java 8 bytecode, so it works in both classic (Java 8) and current (Java 21) Fiji.

3. On the first run, set **cytodl-mini environment** to the environment folder
   (e.g. `C:\Users\you\anaconda3\envs\cytodl-mini` or `.../CytoDL-Mini/.venv`). It is remembered.
   You can also set the `MEGASEG_PYTHON` environment variable instead.

## Use

`Plugins › Segmentation › MegaSeg...`

| Parameter | Default | Meaning |
|---|---|---|
| **Channel to segment** | 1 | 1-based channel of the structure. Ignored for single-channel images. |
| **Output** | Mask | `Mask` (8-bit, 0/255), `Probability map` (8-bit, 0–255 ≙ 0–1), or both. |
| **Probability threshold** | 0.5 | Voxels with probability ≥ threshold are foreground. 0.5 reproduces the paper. |
| **Minimum object size (voxels)** | 0 | Drop 3D connected components smaller than this. |
| *Advanced* | | |
| **Model checkpoint** | empty | Empty = default MegaSeg checkpoint from the cytodl-mini cache. |
| **Device** | auto | `auto` uses the GPU if PyTorch sees one; `cpu` forces CPU. |
| *Environment* | | |
| **cytodl-mini environment** | empty | Conda env / venv folder or python executable. Empty = `MEGASEG_PYTHON` or `python` on PATH. |

Results open as `<image>_MegaSeg_mask` and/or `<image>_MegaSeg_prob` with the input's
XYZ calibration and the same Z and T dimensions. Progress is shown in the status bar; details and
the exact Python command are in the Log window (`Window › Console` for the full Python output).

Notes

- MegaSeg expects 3D stacks acquired near 100x / 0.108 µm xy, 0.29 µm z (the training resolution).
  Other magnifications work but quality drops with the mismatch; consider rescaling first.
- 2D images run but the model is 3D, so expect weaker results.
- RGB images are rejected; convert to a channel stack first.
- Time-lapses: every frame is segmented independently, with the model loaded once.

## Macro / batch

The command is recordable (`Plugins › Macros › Record...`). Parameter keys (case matters):

```
run("MegaSeg...", "channel=1 output=Mask threshold=0.5 min_size=0 device=auto python_env=[C:/Users/you/anaconda3/envs/cytodl-mini]");
```

`output` is one of `Mask`, `[Probability map]`, `[Mask + probability map]`. After the call the
mask is the active image (the probability map, if requested, is the one behind it), so `saveAs`
works as usual, also in headless mode.

Batch example:

```
dir = getDirectory("Choose a folder of TIFFs");
out = dir + "megaseg/";
File.makeDirectory(out);
list = getFileList(dir);
for (i = 0; i < list.length; i++) {
    if (!endsWith(list[i], ".tif")) continue;
    open(dir + list[i]);
    run("MegaSeg...", "channel=1 output=Mask threshold=0.5 min_size=50 device=auto");
    saveAs("Tiff", out + replace(list[i], ".tif", "_MegaSeg_mask.tif"));
    close("*");
}
```

Scripts can call it directly and keep the outputs:

```groovy
#@ CommandService cmd
import org.allencell.megaseg.fiji.MegaSegCommand
def m = cmd.run(MegaSegCommand.class, true, "imp", imp, "channel", 1, "output", "Mask + probability map").get()
def mask = m.getOutput("mask"); def prob = m.getOutput("probability")
```

## Build

No Maven needed; the scripts compile against the jars in your Fiji and use the JDK that ships
with Fiji (or `JAVA_HOME`):

```powershell
.\build.ps1 -Fiji C:\Fiji.app -Install       # Windows
```

```bash
./build.sh /Applications/Fiji.app --install  # macOS / Linux
```

`pom.xml` is a standard pom-scijava project for `mvn package` and for publishing to an update site.

Tests (headless, need the Python env):

```
ImageJ-win64.exe --headless --console --run test/run_headless.groovy "input='vol.tif',outdir='out',python_env='C:/.../envs/cytodl-mini'"
ImageJ-win64.exe --headless --console --run test/run_headless_ct.groovy "input='vol.tif',outdir='out',python_env='...'"   # channel + time-lapse
ImageJ-win64.exe --console --run test/run_gui_display.groovy "input='vol.tif',python_env='...'"                           # windows appear
```

## How it works

```
Fiji (Java)                                  Python (cytodl-mini)
 active image ─ pick channel ─ one TIFF/frame ─┐
                                               ├─ megaseg_bridge.py: predict() once for all frames,
 new hyperstacks ◄─ <frame>_mask/_prob.tif ◄───┘  threshold, remove small objects
```

`src/main/resources/megaseg_bridge.py` is bundled in the jar and extracted to a temp folder for each
run, so the Python environment only needs cytodl-mini itself.

## Troubleshooting

| Message | Fix |
|---|---|
| *Python was not found* | Set **cytodl-mini environment** to the env folder. |
| *cytodl-mini is not installed* | `pip install git+https://github.com/AllenCell/CytoDL-Mini.git` in that env. |
| *default checkpoint could not be downloaded* | `gh auth login`, or download `megaseg.ckpt` and set **Model checkpoint**. |
| *GPU ran out of memory* | Device = `cpu`, or crop the image. |
| Nothing segmented | Check the channel; try a lower threshold and view the probability map. |

On failure the temporary folder is kept and its path is shown in the error dialog.
