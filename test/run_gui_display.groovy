// GUI-mode check: run the command the way the menu does (process=true, outputs displayed)
// and verify that result windows appear, then quit Fiji.
//
//   ImageJ-win64.exe --console --run test/run_gui_display.groovy \
//      "input='D:/data/vol.tif',python_env='C:/.../envs/cytodl-mini'"
//
#@ String input
#@ String python_env
#@ CommandService cmd
#@ UIService ui

import ij.IJ
import ij.WindowManager
import org.allencell.megaseg.fiji.MegaSegCommand

def imp = IJ.openImage(input)
imp.show()
int before = WindowManager.getImageCount()

// In GUI mode every input must be resolved, otherwise SciJava pops the dialog and waits.
def inputs = [imp: imp, channel: 1, output: "Mask + probability map", threshold: 0.5d, min_size: 0,
              checkpoint: null, device: "auto", python_env: new File(python_env),
              headerMain: "", headerAdvanced: "", headerEnv: ""]
cmd.run(MegaSegCommand.class, true, inputs).get()

Thread.sleep(2000)
int after = WindowManager.getImageCount()
println("WINDOWS before=" + before + " after=" + after)
WindowManager.getIDList().each { id ->
    def w = WindowManager.getImage(id)
    println("WINDOW " + w.getTitle() + " Z=" + w.getNSlices() + " T=" + w.getNFrames() + " bits=" + w.getBitDepth())
}
println(after == before + 2 ? "DISPLAY_OK" : "DISPLAY_FAIL")
System.exit(0)
