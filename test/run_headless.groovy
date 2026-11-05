// Headless end-to-end test for the MegaSeg Fiji plugin.
//
//   ImageJ-win64.exe --headless --console --run test/run_headless.groovy \
//      "input='D:/data/vol.tif',outdir='D:/tmp/out',python_env='C:/.../envs/cytodl-mini',channel=1"
//
#@ String input
#@ String outdir
#@ String python_env
#@ Integer (value = 1) channel
#@ CommandService cmd
#@ LogService log

import ij.IJ
import ij.process.StackStatistics
import org.allencell.megaseg.fiji.MegaSegCommand

new File(outdir).mkdirs()
def imp = IJ.openImage(input)
if (imp == null) throw new RuntimeException("Could not open " + input)
println("INPUT " + imp.getTitle() + " C=" + imp.getNChannels() + " Z=" + imp.getNSlices() + " T=" + imp.getNFrames()
        + " bits=" + imp.getBitDepth())

long t0 = System.currentTimeMillis()
def module = cmd.run(MegaSegCommand.class, true,
        "imp", imp,
        "channel", channel,
        "output", "Mask + probability map",
        "threshold", 0.5d,
        "min_size", 100,
        "device", "auto",
        "python_env", new File(python_env)).get()
println("ELAPSED " + ((System.currentTimeMillis() - t0) / 1000.0) + " s")

def mask = module.getOutput("mask")
def prob = module.getOutput("probability")
if (mask == null || prob == null) throw new RuntimeException("Command produced no outputs (see log above)")

def ms = new StackStatistics(mask)
def ps = new StackStatistics(prob)
long nvox = (long) mask.getWidth() * mask.getHeight() * mask.getStackSize()
println("MASK  " + mask.getTitle() + " C=" + mask.getNChannels() + " Z=" + mask.getNSlices() + " T=" + mask.getNFrames()
        + " bits=" + mask.getBitDepth() + " min=" + ms.min + " max=" + ms.max
        + " fgVoxels=" + Math.round(ms.mean / 255.0 * nvox) + " cal=" + mask.getCalibration().pixelWidth + "/" + mask.getCalibration().pixelDepth)
println("PROB  " + prob.getTitle() + " Z=" + prob.getNSlices() + " T=" + prob.getNFrames()
        + " bits=" + prob.getBitDepth() + " min=" + ps.min + " max=" + ps.max + " mean=" + ps.mean)

IJ.saveAsTiff(mask, outdir + "/mask.tif")
IJ.saveAsTiff(prob, outdir + "/prob.tif")
println("SAVED " + outdir)
