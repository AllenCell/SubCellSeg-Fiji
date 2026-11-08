// Headless test of the "Minimum object size" filter. Needs no Python and no image.
//
//   ImageJ-win64.exe --headless --console --run test/run_small_objects.groovy
//
// Optional: filter a real 8-bit mask and save it, to compare with another tool:
//   ... --run test/run_small_objects.groovy "input='mask.tif',output='filtered.tif',min_size=100"
//
#@ String (value = "", persist = false) input
#@ String (value = "", persist = false) output
#@ Integer (value = 100, persist = false) min_size

import ij.IJ
import ij.ImageStack
import ij.process.ByteProcessor
import org.allencell.subcellseg.fiji.SubCellSegCommand

// --- synthetic volume: 12 x 10 x 4 (X, Y, Z), repeated as two "frames" in one stack
int w = 12, h = 10, nZ = 4
def stack = new ImageStack(w, h)
(2 * nZ).times { stack.addSlice(new ByteProcessor(w, h)) }
def set = { int frame, int x, int y, int z -> stack.getProcessor(frame * nZ + z + 1).set(x, y, 255) }
def get = { int frame, int x, int y, int z -> stack.getProcessor(frame * nZ + z + 1).get(x, y) }

2.times { f ->
    set(f, 0, 0, 0)                                   // A: 1 voxel in a corner
    (0..3).each { z -> set(f, 5, 5, z) }              // B: 4 voxels along Z
    (2..6).each { x -> set(f, x, 8, 1) }              // C: 5 voxels along X
    set(f, 9, 1, 2); set(f, 10, 2, 2); set(f, 11, 3, 3)   // D: 3 voxels touching only diagonally
    (8..10).each { x -> (5..6).each { y -> set(f, x, y, 3) } }   // E: 6 voxels in a plane
}
set(1, 0, 0, 3)                                       // only in frame 2: must not join frame 1

// Frame 1, minimum size 5: A (1), B (4) and the three single voxels of D go; C (5) and E (6) stay.
long removed1 = SubCellSegCommand.removeSmallObjects(stack, 1, nZ, 5)
// Frame 2, minimum size 6: everything but E goes, including the extra voxel.
long removed2 = SubCellSegCommand.removeSmallObjects(stack, nZ + 1, nZ, 6)

def count = { int f -> (0..<nZ).sum { z -> (0..<h).sum { y -> (0..<w).count { x -> get(f, x, y, z) == 255 } } } }
def other = { int f -> (0..<nZ).sum { z -> (0..<h).sum { y -> (0..<w).count { x -> !(get(f, x, y, z) in [0, 255]) } } } }
println("SYNTHETIC removed=" + removed1 + "/" + removed2 + " left=" + count(0) + "/" + count(1))
boolean ok = removed1 == 5 && count(0) == 11 && get(0, 2, 8, 1) == 255 && get(0, 9, 5, 3) == 255 && get(0, 5, 5, 0) == 0 &&
        removed2 == 7 && count(1) == 6 && get(1, 9, 5, 3) == 255 && get(1, 2, 8, 1) == 0 &&
        other(0) == 0 && other(1) == 0
println(ok ? "SMALL_OBJECTS_OK" : "SMALL_OBJECTS_FAIL")

if (!input.isEmpty()) {
    def imp = IJ.openImage(input)
    long t0 = System.currentTimeMillis()
    long n = SubCellSegCommand.removeSmallObjects(imp.getStack(), 1, imp.getStackSize(), min_size)
    println("REAL removed=" + n + " in " + (System.currentTimeMillis() - t0) + " ms")
    IJ.saveAsTiff(imp, output)
}
