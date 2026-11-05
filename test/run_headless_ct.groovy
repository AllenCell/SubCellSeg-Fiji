// Headless test of channel selection + time-lapse handling.
// Builds a synthetic 2-channel, 2-frame hyperstack from a single 3D volume:
//   channel 1 = noise (should NOT be segmented), channel 2 = the real data,
//   frame 2   = frame 1 translated by 50 px in x.
// Then runs SubCellSeg on channel 2 and checks dimensions, calibration and that the
// frame-2 mask is (approximately) the frame-1 mask shifted by 50 px.
//
//   ImageJ-win64.exe --headless --console --run test/run_headless_ct.groovy \
//      "input='D:/data/vol.tif',outdir='D:/tmp/out',python_env='C:/.../envs/subcellseg'"
//
#@ String input
#@ String outdir
#@ String python_env
#@ CommandService cmd

import ij.IJ
import ij.ImagePlus
import ij.ImageStack
import ij.plugin.Duplicator
import ij.process.StackStatistics
import org.allencell.subcellseg.fiji.SubCellSegCommand

new File(outdir).mkdirs()
def src = IJ.openImage(input)
int w = src.getWidth(), h = src.getHeight(), nZ = src.getNSlices()
int SHIFT = 50

def stack = new ImageStack(w, h)
for (int t = 1; t <= 2; t++) {
    for (int z = 1; z <= nZ; z++) {
        for (int c = 1; c <= 2; c++) {
            def ip = src.getStack().getProcessor(z).duplicate()
            if (c == 1) { ip = ip.createProcessor(w, h); ip.noise(500) }
            if (t == 2) { ip.translate(SHIFT, 0) }
            stack.addSlice(ip)
        }
    }
}
def hyper = new ImagePlus("synthetic_CT.tif", stack)
hyper.setDimensions(2, nZ, 2)
hyper.setOpenAsHyperStack(true)
def cal = hyper.getCalibration()
cal.pixelWidth = 0.108; cal.pixelHeight = 0.108; cal.pixelDepth = 0.29; cal.setUnit("micron")
println("INPUT C=" + hyper.getNChannels() + " Z=" + hyper.getNSlices() + " T=" + hyper.getNFrames())

def module = cmd.run(SubCellSegCommand.class, true,
        "imp", hyper, "channel", 2, "output", "Mask", "threshold", 0.5d, "min_size", 0,
        "device", "auto", "python_env", new File(python_env)).get()
def mask = module.getOutput("mask")
if (mask == null) throw new RuntimeException("no mask output")
println("MASK " + mask.getTitle() + " C=" + mask.getNChannels() + " Z=" + mask.getNSlices() + " T=" + mask.getNFrames()
        + " cal=" + mask.getCalibration().pixelWidth + "/" + mask.getCalibration().pixelDepth + " " + mask.getCalibration().getUnit())

def f1 = new Duplicator().run(mask, 1, 1, 1, nZ, 1, 1)
def f2 = new Duplicator().run(mask, 1, 1, 1, nZ, 2, 2)
long nvox = (long) w * h * nZ
long fg1 = Math.round(new StackStatistics(f1).mean / 255.0 * nvox)
long fg2 = Math.round(new StackStatistics(f2).mean / 255.0 * nvox)
println("FG frame1=" + fg1 + " frame2=" + fg2)

// Dice between frame1 shifted by SHIFT and frame2 (ignoring the wrapped border region).
long inter = 0, a = 0, b = 0
for (int z = 1; z <= nZ; z++) {
    def p1 = f1.getStack().getProcessor(z), p2 = f2.getStack().getProcessor(z)
    for (int y = 0; y < h; y++) for (int x = SHIFT + 2; x < w - 2; x++) {
        boolean m1 = p1.get(x - SHIFT, y) > 0, m2 = p2.get(x, y) > 0
        if (m1) a++; if (m2) b++; if (m1 && m2) inter++
    }
}
double dice = 2.0 * inter / (a + b + 1e-9)
println("SHIFT_DICE " + String.format("%.4f", dice))
IJ.saveAsTiff(mask, outdir + "/mask_ct.tif")
boolean ok = mask.getNSlices() == nZ && mask.getNFrames() == 2 && mask.getNChannels() == 1 &&
        Math.abs(mask.getCalibration().pixelDepth - 0.29) < 1e-9 && fg1 > 10000 && dice > 0.9
println(ok ? "CT_OK" : "CT_FAIL")
