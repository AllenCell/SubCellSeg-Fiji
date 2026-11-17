// Runs "SubCellSeg..." headless with no image open. Expected: the command fails cleanly with
// "No image is open." (in the GUI it would offer to open the sample image instead).
//   fiji --headless --run test/run_noimage.groovy
#@ CommandService cmd
#@ LogService log

import ij.IJ
import ij.WindowManager
import org.allencell.subcellseg.fiji.SubCellSegCommand

WindowManager.closeAllWindows()
def module = cmd.run(SubCellSegCommand.class, true).get()
println(module.getOutput("mask") == null && module.getOutput("probability") == null ? "NOIMAGE_OK" : "NOIMAGE_FAIL (produced output)")
