# Testing the plugin on macOS

Everything here runs from a terminal and prints `..._OK` / `..._FAIL` markers, so it can be
handed to a person or to an agent. Apple Silicon is assumed; on an Intel Mac use the
`macos64` Fiji download instead of `macos-arm64`.

What is being checked:

1. the plugin compiles with the JDK that ships with Fiji and loads in a current (Java 21) Fiji;
2. the **Setup** command installs SubCellSeg from inside Fiji (uv install, `uv tool install`, model download);
3. the segmentation command finds that installation by itself and reproduces the reference mask on CPU;
4. the dialogs look right (quick manual check at the end).

There is no CUDA on macOS, so SubCellSeg runs on the CPU there. The sample volume
(40 × 256 × 256) takes a minute or two on an M-series chip.

## 0. Get the plugin source onto the Mac

```bash
git clone <URL of the SubCellSeg-Fiji repository> ~/SubCellSeg-Fiji
cd ~/SubCellSeg-Fiji
```

(or copy the `fiji-plugin` folder over by any means; it is about 15 MB).

## 1. Fiji

```bash
cd ~
curl -sSL -o fiji.zip https://downloads.imagej.net/fiji/latest/fiji-latest-macos-arm64-jdk.zip
unzip -q fiji.zip                       # gives ~/Fiji.app
xattr -dr com.apple.quarantine ~/Fiji.app   # avoid the Gatekeeper "damaged app" dialog
ls ~/Fiji.app/Contents/MacOS/           # note the launcher name (fiji, or ImageJ-macosx on classic builds)
```

## 2. Automated tests

Run with a scratch uv tool directory so the test installation does not touch anything else:

```bash
cd ~/SubCellSeg-Fiji
export UV_TOOL_DIR=~/subcellseg-test-tools
test/run_tests.sh ~/Fiji.app --setup
```

The script builds and installs the jar, then runs three Fiji sessions headless. Expected
markers in the output:

| Marker | Meaning |
|---|---|
| `SMALL_OBJECTS_OK` | Java-only size filter works |
| `SETUP_OK` + a report with `torch`, `gpu none ...` and `Model: .../subcellseg_v1.pt` | Setup command installed uv (if needed), SubCellSeg and the model |
| `SAMPLE_OK` with `DICE_VS_REFERENCE 0.99xx` | segmentation of the bundled sample on CPU matches the GPU reference |

It ends with `passed: ...` / `failed: ...` and a non-zero exit code if anything failed.
The `SETUP` step downloads PyTorch (about 200 MB on macOS) and can take several minutes.

If `SETUP_OK` appears but `SAMPLE_OK` does not, run the sample test alone with the explicit
environment to separate "install" from "detection" problems:

```bash
test/run_tests.sh ~/Fiji.app --skip-build --python-env "$UV_TOOL_DIR/subcellseg"
```

## 3. Manual check of the dialogs

```bash
open ~/Fiji.app
```

1. `Plugins › Segmentation › SubCellSeg Sample Image` opens a 40-slice stack.
2. `Plugins › Segmentation › SubCellSeg...`: leave everything at its defaults, OK. The status bar shows
   progress; a window `SubCellSeg_sample_SON_SubCellSeg_mask` appears after a minute or two.
3. `Plugins › Segmentation › SubCellSeg Setup...` with *Action* = `Check the installation`, OK:
   a message box with the `subcellseg info` lines appears.
4. Take a screenshot of the SubCellSeg dialog and of the mask next to the sample.

## 4. What to report back

- the complete terminal output of step 3 (or at least the last 60 lines of each Fiji session);
- `~/Fiji.app/Contents/MacOS/` listing and macOS version (`sw_vers`);
- `$UV_TOOL_DIR/subcellseg/bin/python -m subcellseg info`;
- the elapsed time printed on the `MASK ... elapsed=` line;
- the screenshots from step 4 and anything that looked wrong.

## Prompt for an agent

> In `~/SubCellSeg-Fiji` follow `docs/MAC_TEST.md` from section 1 to section 3. Do not change any
> source file; if a step fails, collect the error and continue with the remaining steps where
> possible. Finish with the report listed in section 4, including the exact commands that failed.
