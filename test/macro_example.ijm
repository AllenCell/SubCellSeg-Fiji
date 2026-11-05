// Macro form of the command (keys = parameter names). Run headless:
//   ImageJ-win64.exe --headless --console --run test/macro_example.ijm
// Edit the three paths below for your machine. Leave python_env out if SubCellSeg
// was installed with its standard instructions.
open("D:/Work/MegaSeg/Data/Jianxu_Data/megaseg_predictions/rescaled/raw_input/M0_20_C0.tif");
run("SubCellSeg...", "channel=1 output=Mask threshold=0.5 min_size=50 device=auto python_env=[D:/Work/MegaSeg/code-release/.venv]");
print("MACRO_TITLE " + getTitle() + " slices=" + nSlices + " bits=" + bitDepth());
getStatistics(area, mean);
print("MACRO_MEAN " + mean);
saveAs("Tiff", "D:/Work/MegaSeg/fiji-plugin/test/out_sub_m0/macro_mask.tif");
print("MACRO_DONE");
