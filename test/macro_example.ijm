// Macro form of the command (keys = parameter names). Run headless:
//   ImageJ-win64.exe --headless --console --run test/macro_example.ijm
// Edit the two paths below for your machine. Add python_env=[/path/to/env] to the
// run() call if SubCellSeg lives in a conda env or venv rather than the Setup install.
open("/path/to/your_image.tif");
run("SubCellSeg...", "channel=1 output=Mask threshold=0.5 min_size=50 device=auto");
print("MACRO_TITLE " + getTitle() + " slices=" + nSlices + " bits=" + bitDepth());
getStatistics(area, mean);
print("MACRO_MEAN " + mean);
saveAs("Tiff", "/path/to/output_mask.tif");
print("MACRO_DONE");
