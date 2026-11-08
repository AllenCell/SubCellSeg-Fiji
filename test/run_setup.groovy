// Runs the "SubCellSeg Setup..." command headless: installs (or checks) the SubCellSeg
// Python package the way a user would from the menu, and prints the report.
//
//   fiji --headless --run test/run_setup.groovy "source='/path/to/SubCellSeg',download_model=true"
//   fiji --headless --run test/run_setup.groovy "action='Check the installation'"
//
// To keep a test installation apart from the real one, start Fiji with the UV_TOOL_DIR
// environment variable pointing to a scratch folder: both the setup and the segmentation
// command honour it.
//
#@ String (value = "https://github.com/AllenCell/SubCellSeg/archive/refs/heads/main.zip", persist = false) source
#@ String (value = "Install or update SubCellSeg", persist = false) action
#@ String (value = "auto (detect the GPU)", persist = false) torch_backend
#@ Boolean (value = false, persist = false) extra_formats
#@ Boolean (value = true, persist = false) download_model
#@ CommandService cmd

import org.allencell.subcellseg.fiji.SubCellSegSetupCommand

long t0 = System.currentTimeMillis()
def module = cmd.run(SubCellSegSetupCommand.class, true,
        "action", action, "torch_backend", torch_backend, "extra_formats", extra_formats,
        "download_model", download_model, "source", source).get()
def report = module.getOutput("report")
println("SETUP_REPORT\n" + report)
println("SETUP_ELAPSED " + String.format("%.0f", (System.currentTimeMillis() - t0) / 1000.0) + " s")
println(report != null && !report.startsWith("FAILED") ? "SETUP_OK" : "SETUP_FAIL")
