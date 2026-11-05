// Opens the SubCellSeg dialog on a test image, screenshots it to <out>, then quits Fiji.
// The dialog shows the default values: remembered settings are cleared first.
//   ImageJ-win64.exe --console --run test/screenshot_dialog.groovy "input='vol.tif',out='docs/dialog.png'"
#@ String input
#@ String out
#@ CommandService cmd
#@ PrefService prefs

import ij.IJ
import java.awt.Robot
import java.awt.Window
import javax.imageio.ImageIO
import javax.swing.JDialog
import org.allencell.subcellseg.fiji.SubCellSegCommand

prefs.clear(SubCellSegCommand.class)
def imp = IJ.openImage(input)
imp.show()
cmd.run(SubCellSegCommand.class, true, "imp", imp)   // unresolved inputs -> dialog pops up

// Poll from a worker thread so the event-dispatch thread stays free to show the dialog.
Thread.start {
    def isOurs = { Window w -> w instanceof JDialog && w.isShowing() && (w.getTitle() ?: "").startsWith("SubCellSeg") }
    Window dialog = null
    for (int i = 0; i < 60 && dialog == null; i++) {
        Thread.sleep(500)
        dialog = Window.getWindows().find(isOurs)
    }
    if (dialog == null) { println("DIALOG_NOT_FOUND"); System.exit(1) }
    // Other pop-ups (e.g. Fiji's "updates available" prompt) can open on top a moment later: close them.
    for (int i = 0; i < 8; i++) {
        Thread.sleep(500)
        Window.getWindows().findAll { it instanceof JDialog && it.isShowing() && !isOurs(it) }.each {
            println("CLOSING " + it.getTitle())
            it.dispose()
        }
    }
    dialog.toFront()
    Thread.sleep(1000)
    def b = dialog.getBounds()
    def img = new Robot().createScreenCapture(b)
    new File(out).getParentFile()?.mkdirs()
    ImageIO.write(img, "png", new File(out))
    println("SCREENSHOT " + out + " " + b.width + "x" + b.height)
    System.exit(0)
}
