// Macro form of the command (keys = parameter names). Run headless:
//   ImageJ-win64.exe --headless --console --run test/macro_example.ijm
open("D:/Work/MegaSeg/Data/Jianxu_Data/megaseg_predictions/rescaled/raw_input/M0_20_C0.tif");
run("MegaSeg...", "channel=1 output=Mask threshold=0.5 min_size=50 device=auto python_env=[C:/Users/suraj.mishra/AppData/Local/anaconda3/envs/cytodl-mini]");
print("MACRO_TITLE " + getTitle() + " slices=" + nSlices + " bits=" + bitDepth());
getStatistics(area, mean);
print("MACRO_MEAN " + mean);
saveAs("Tiff", "D:/Work/MegaSeg/fiji-plugin/test/out_m0/macro_mask.tif");
print("MACRO_DONE");
