// Opens the MegaSeg dialog on a test image, screenshots it to <out>, then quits Fiji.
//   ImageJ-win64.exe --console --run test/screenshot_dialog.groovy "input='vol.tif',out='docs/dialog.png'"
#@ String input
#@ String out
#@ CommandService cmd

import ij.IJ
import java.awt.Robot
import java.awt.Window
import javax.imageio.ImageIO
import org.allencell.megaseg.fiji.MegaSegCommand

def imp = IJ.openImage(input)
imp.show()
cmd.run(MegaSegCommand.class, true, "imp", imp)   // unresolved inputs -> dialog pops up

// Poll from a worker thread so the event-dispatch thread stays free to show the dialog.
Thread.start {
    Window dialog = null
    for (int i = 0; i < 60 && dialog == null; i++) {
        Thread.sleep(500)
        dialog = Window.getWindows().find { it instanceof javax.swing.JDialog && it.isShowing() && it.getWidth() > 200 }
    }
    if (dialog == null) { println("DIALOG_NOT_FOUND"); System.exit(1) }
    Thread.sleep(1500)
    def b = dialog.getBounds()
    def img = new Robot().createScreenCapture(b)
    new File(out).getParentFile()?.mkdirs()
    ImageIO.write(img, "png", new File(out))
    println("SCREENSHOT " + out + " " + b.width + "x" + b.height)
    System.exit(0)
}
