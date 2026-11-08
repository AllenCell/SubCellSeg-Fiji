// End-to-end test on the sample image that ships inside the plugin jar.
// Opens the sample with the "SubCellSeg Sample Image" command, segments it, and
// (optionally) compares the mask with a reference mask.
//
//   fiji --headless --run test/run_sample.groovy \
//      "python_env='',expected='test/data/SubCellSeg_sample_SON_expected_mask.tif',outdir='test/out_sample'"
//
// python_env = '' uses the automatically found SubCellSeg installation.
//
#@ String (value = "", persist = false) python_env
#@ String (value = "", persist = false) expected
#@ String (value = "", persist = false) outdir
#@ String (value = "auto", persist = false) device
#@ CommandService cmd

import ij.IJ
import org.allencell.subcellseg.fiji.SubCellSegCommand
import org.allencell.subcellseg.fiji.SubCellSegSampleCommand

def imp = cmd.run(SubCellSegSampleCommand.class, true).get().getOutput("sample")
if (imp == null) throw new RuntimeException("The sample image could not be opened")
def cal = imp.getCalibration()
println("SAMPLE " + imp.getTitle() + " " + imp.getWidth() + "x" + imp.getHeight() + " Z=" + imp.getNSlices()
        + " bits=" + imp.getBitDepth() + " cal=" + cal.pixelWidth + "/" + cal.pixelDepth + " " + cal.getUnit())

long t0 = System.currentTimeMillis()
def module = cmd.run(SubCellSegCommand.class, true,
        "imp", imp, "channel", 1, "output", "Mask", "threshold", 0.5d, "min_size", 0,
        "device", device, "python_env", new File(python_env)).get()
double secs = (System.currentTimeMillis() - t0) / 1000.0
def mask = module.getOutput("mask")
if (mask == null) throw new RuntimeException("SubCellSeg produced no mask (see the log above)")

def fgCount = { ij.ImagePlus m ->
    long n = 0
    for (int z = 1; z <= m.getStackSize(); z++) {
        byte[] px = (byte[]) m.getStack().getPixels(z)
        for (int i = 0; i < px.length; i++) if (px[i] != 0) n++
    }
    n
}
long fg = fgCount(mask)
println("MASK " + mask.getTitle() + " Z=" + mask.getNSlices() + " T=" + mask.getNFrames() + " bits=" + mask.getBitDepth()
        + " fg=" + fg + " cal=" + mask.getCalibration().pixelWidth + "/" + mask.getCalibration().pixelDepth
        + " elapsed=" + String.format("%.1f", secs) + "s")

boolean ok = mask.getWidth() == imp.getWidth() && mask.getHeight() == imp.getHeight() &&
        mask.getNSlices() == imp.getNSlices() && mask.getBitDepth() == 8 &&
        Math.abs(mask.getCalibration().pixelDepth - cal.pixelDepth) < 1e-9 && fg > 1000

if (!expected.isEmpty()) {
    def ref = IJ.openImage(expected)
    if (ref == null) throw new RuntimeException("Could not open the reference mask " + expected)
    long inter = 0, a = 0, b = 0
    for (int z = 1; z <= mask.getStackSize(); z++) {
        byte[] p = (byte[]) mask.getStack().getPixels(z)
        byte[] q = (byte[]) ref.getStack().getPixels(z)
        for (int i = 0; i < p.length; i++) {
            boolean m1 = p[i] != 0, m2 = q[i] != 0
            if (m1) a++; if (m2) b++; if (m1 && m2) inter++
        }
    }
    double dice = 2.0 * inter / (a + b + 1e-9)
    // The reference was computed on a GPU; CPU and other GPUs differ in the last bits only.
    println("DICE_VS_REFERENCE " + String.format("%.4f", dice) + " (reference fg=" + b + ")")
    ok = ok && dice > 0.97
}

if (!outdir.isEmpty()) {
    new File(outdir).mkdirs()
    IJ.saveAsTiff(mask, outdir + "/sample_mask.tif")
    println("SAVED " + outdir + "/sample_mask.tif")
}
println(ok ? "SAMPLE_OK" : "SAMPLE_FAIL")
