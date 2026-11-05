# SubCellSeg for Fiji

One-click [SubCellSeg](https://github.com/AllenCell/SubCellSeg) segmentation of 3D fluorescence
volumes from inside Fiji. Open an image, choose `Plugins › Segmentation › SubCellSeg...`, press OK.

SubCellSeg is a single generalist model: there is no structure to pick, no diameter to tune.
The plugin sends the selected channel to SubCellSeg, which runs in Python, and returns a binary mask
and/or an 8-bit probability map as new, calibrated images. Time-lapses are processed frame by frame.
The mask is the same one `subcellseg predict` gives on the command line.

![dialog](docs/dialog.png)

## Install

1. **Install SubCellSeg** (once), by following its
   [installation instructions](https://github.com/AllenCell/SubCellSeg/blob/main/docs/INSTALL.md).
   It is two commands in a terminal. When `subcellseg info` works there, you are done with this step.

2. **Add the plugin to Fiji.** Copy `SubCellSeg_Fiji-<version>.jar` into `Fiji.app/plugins/` and
   restart Fiji. The jar is Java 8 bytecode, so it works in both classic (Java 8) and current
   (Java 21) Fiji.

That is all: the plugin finds the SubCellSeg installation from step 1 by itself. The model
(about 65 MB) is downloaded automatically the first time you run it.

If you installed SubCellSeg another way (in a conda environment or a venv), tell the plugin where:
set **Python environment** in the dialog to that environment's folder, for example
`C:\Users\you\anaconda3\envs\subcellseg`. It is remembered. Setting the `SUBCELLSEG_PYTHON`
environment variable does the same.

## Use

`Plugins › Segmentation › SubCellSeg...`

| Parameter | Default | Meaning |
|---|---|---|
| **Channel to segment** | 1 | 1-based channel of the structure. Ignored for single-channel images. |
| **Output** | Mask | `Mask` (8-bit, 0/255), `Probability map` (8-bit, 0–255 ≙ 0–1), or both. |
| **Probability threshold** | 0.5 | Voxels with a probability above the threshold are foreground. 0.5 is the standard setting. |
| **Minimum object size (voxels)** | 0 | Remove 3D objects (voxels touching face to face) smaller than this. 0 keeps everything. |
| *Advanced* | | |
| **Model checkpoint** | empty | Empty = the pretrained SubCellSeg model. Choose a file to use a model you fine-tuned (`best.ckpt`). |
| **Device** | auto | `auto` uses the GPU if PyTorch sees one; `cpu` forces CPU. |
| *Environment* | | |
| **Python environment** | empty | Empty = found automatically (see Install). Otherwise a conda env / venv folder or a python executable. |

Results open as `<image>_SubCellSeg_mask` and/or `<image>_SubCellSeg_prob` with the input's
XYZ calibration and the same Z and T dimensions. Progress is shown in the status bar; details and
the exact Python command are in the Log window (`Window › Console` for the full Python output).

Notes

- SubCellSeg expects 3D stacks acquired near 100x / 0.108 µm xy, 0.29 µm z (the training resolution).
  Other magnifications work but quality drops with the mismatch; consider rescaling first.
- 2D images run but the model is 3D, so expect weaker results.
- RGB images are rejected; convert to a channel stack first.
- Time-lapses: every frame is segmented independently, with the model loaded once.

## Macro / batch

The command is recordable (`Plugins › Macros › Record...`). Parameter keys (case matters):

```
run("SubCellSeg...", "channel=1 output=Mask threshold=0.5 min_size=0 device=auto");
```

`output` is one of `Mask`, `[Probability map]`, `[Mask + probability map]`. Add
`python_env=[C:/path/to/env]` only if SubCellSeg is not found automatically. After the call the
mask is the active image (the probability map, if requested, is the one behind it), so `saveAs`
works as usual, also in headless mode.

Batch example:

```
dir = getDirectory("Choose a folder of TIFFs");
out = dir + "subcellseg/";
File.makeDirectory(out);
list = getFileList(dir);
for (i = 0; i < list.length; i++) {
    if (!endsWith(list[i], ".tif")) continue;
    open(dir + list[i]);
    run("SubCellSeg...", "channel=1 output=Mask threshold=0.5 min_size=50 device=auto");
    saveAs("Tiff", out + replace(list[i], ".tif", "_SubCellSeg_mask.tif"));
    close("*");
}
```

Scripts can call it directly and keep the outputs:

```groovy
#@ CommandService cmd
import org.allencell.subcellseg.fiji.SubCellSegCommand
def m = cmd.run(SubCellSegCommand.class, true, "imp", imp, "channel", 1, "output", "Mask + probability map").get()
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

Tests (headless). `python_env` is the folder of an environment with SubCellSeg; pass `''` to use
the automatically found one. Add `--class-path build/SubCellSeg_Fiji-<version>.jar` after
`ImageJ-win64.exe` to test a freshly built jar without installing it into Fiji.

```
ImageJ-win64.exe --headless --console --run test/run_small_objects.groovy                                                # size filter, no Python needed
ImageJ-win64.exe --headless --console --run test/run_headless.groovy "input='vol.tif',outdir='out',python_env='...'"
ImageJ-win64.exe --headless --console --run test/run_headless_ct.groovy "input='vol.tif',outdir='out',python_env='...'"   # channel + time-lapse
ImageJ-win64.exe --console --run test/run_gui_display.groovy "input='vol.tif',python_env='...'"                           # windows appear
```

## How it works

```
Fiji (Java)                                  Python (subcellseg)
 active image ─ pick channel ─ one TIFF/frame ─┐
                                               ├─ subcellseg_bridge.py: load the model once,
 new hyperstacks ◄─ <frame>_mask/_prob.tif ◄───┘  predict and threshold every frame
 remove small objects (in Java)
```

`src/main/resources/subcellseg_bridge.py` is bundled in the jar and extracted to a temp folder for each
run, so the Python environment only needs the `subcellseg` package itself.

The Python used is, in this order: the **Python environment** set in the dialog, the
`SUBCELLSEG_PYTHON` environment variable, the environment that `uv tool install` creates for
SubCellSeg, and finally `python` on the PATH.

## Troubleshooting

| Message | Fix |
|---|---|
| *Python was not found* / *SubCellSeg is not installed* | Install SubCellSeg (step 1 of Install). If it is installed in a conda env or venv, set **Python environment** to that folder. |
| *model could not be downloaded* | Download `subcellseg_v1.pt` from the [releases page](https://github.com/AllenCell/SubCellSeg/releases/tag/weights-v1) and select it under **Model checkpoint**. |
| It is very slow | Run `subcellseg info` in a terminal. If the `gpu` line does not name your graphics card, see the SubCellSeg installation instructions. |
| *GPU ran out of memory* | Device = `cpu`, or crop the image. |
| Nothing segmented | Check the channel; try a lower threshold and view the probability map. |

On failure the temporary folder is kept and its path is shown in the error dialog.
