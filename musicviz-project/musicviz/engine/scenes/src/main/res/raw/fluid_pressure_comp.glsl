#version 310 es
precision highp float;
precision highp int;
layout(local_size_x = %%LOCAL_SIZE_X%%, local_size_y = %%LOCAL_SIZE_Y%%) in;

// FLUID / PRESSURE - the Jacobi solve, tiled through shared memory.
//
// The compute-tier twin of fluid_pressure_frag.glsl. It is not a variation on
// that pass, it is the SAME pass: same stencil, same operand order, same
// boundary rule. The ES 3.0 fragment path stays the baseline forever and this
// file runs only where GlTier.Compute was PROVEN (ES >= 3.1, >= 128 work-group
// invocations, >= 4 compute image uniforms). If the two ever disagree by more
// than the divergences documented at the bottom of this file, the compute path
// is wrong, not the fragment one.
//
//     p' = (L + R + B + T + alpha * div) * 0.25       alpha = -dx*dx
//
// with Neumann boundaries: a neighbour outside the domain reads the edge texel.
// The fragment pass spells that as a uv clamp into [inv*0.5, 1 - inv*0.5] and
// a NEAREST fetch; in texel space it is clamp(coord, 0, size-1), which is the
// identical operation without the round trip through 1/res.
//
// WHY THIS PASS IS THE FIRST COMPUTE KERNEL IN THE ENGINE
//
// pressureIterations defaults to 20 and runs every frame. In the fragment path
// each of those 20 iterations is a full ping-pong: bind an FBO, resolve a tile,
// read 4 pressure texels plus 1 divergence texel per fragment, write 1, unbind.
// The stencil is tiny and the data is reused four times by its neighbours - the
// textbook case for staging a tile in shared memory and iterating there.
//
// A work group loads its own tile plus a HALO-wide ring ONCE and then runs up
// to HALO iterations without touching memory again. Each iteration consumes one
// ring of the halo (the classic overlapped/temporally-blocked tiling), so after
// HALO iterations exactly the tile is still valid - which is what gets stored.
//
// Cost, for a 16x8 tile with HALO = 4 (the recommended default):
//   fragment: 4 iterations x (5 reads + 1 write) = 24 texel touches per texel,
//             plus 4 FBO binds and 4 tile resolves.
//   compute:  (24 x 16) x 2 loads / 128 texels = 6 reads + 1 write per texel,
//             in ONE dispatch with no framebuffer involved at all.
// That is ~3.4x fewer texel touches and 5 dispatches instead of 20 bind/resolve
// pairs for the default 20 iterations. §6.3's instruction is to budget
// bandwidth, not ALU: the extra arithmetic the halo re-computes at the tile
// borders is free, the memory traffic it removes is not.
//
// What this kernel does NOT buy is the tiler argument. §6.3's "the binding
// constraint for scattered geometry is the tiler, not the ROP" is the strongest
// case for the compute tier, but it is a case about SCATTER - deposit quads
// landing at random tiles and forcing per-primitive, per-tile records through
// the polygon-list build. A pressure solve is a dense, perfectly-coherent
// stencil that never scatters, so its win is bandwidth and pass overhead only.
// Saying so here keeps the tiler argument for the kernels it actually describes
// (the splat and deposit passes), where it is decisive rather than decorative.
//
// SUBSTITUTION CONTRACT (the dispatch layer performs plain string replacement):
//   %%LOCAL_SIZE_X%%  work-group width  in invocations   (default 16)
//   %%LOCAL_SIZE_Y%%  work-group height in invocations   (default 8)
//   %%JACOBI_HALO%%   iterations fused per dispatch      (default 4)
//   %%MATCH_HALF%%    1 or 0, see GEODE_MATCH_HALF below (default 1)
// The tokens are deliberately not valid GLSL: a forgotten substitution fails at
// glCompileShader with a syntax error on a named line, instead of silently
// running at some default nobody chose.
//
// Shared memory used = 4 * 3 * (LOCAL_SIZE_X + 2*HALO) * (LOCAL_SIZE_Y + 2*HALO)
// bytes. The dispatch layer must check that against GL_MAX_COMPUTE_SHARED_MEMORY_SIZE
// (ES 3.1 floor: 16384) before choosing a local size. 16x8 with HALO 4 is 4608.

#define GEODE_MATCH_HALF %%MATCH_HALF%%

const int HALO = %%JACOBI_HALO%%;

// gl_WorkGroupSize is a compile-time constant in ESSL 3.10, so the shared tile
// sizes derive from the layout above rather than from a second pair of
// substituted tokens. One token, one meaning: a local size and a tile size that
// disagreed would be a silent out-of-bounds, and there is no way to assert at
// runtime inside a shader.
const int TILE_W = int(gl_WorkGroupSize.x);
const int TILE_H = int(gl_WorkGroupSize.y);
const int LOAD_W = TILE_W + 2 * HALO;
const int LOAD_H = TILE_H + 2 * HALO;
const int LOAD_CELLS = LOAD_W * LOAD_H;

// State is RGBA32UI with float bits packed into uint channels, per §6.3:
// EXT_color_buffer_float is not core in ES 3.0 and RGBA32UI is, so the baseline
// and the compute tier can hold bit-identical state. ES 3.1 requires both
// rgba32ui and rgba32f for image load/store, but only RGBA32UI is colour-
// renderable in core ES 3.0, and the baseline has to be able to render into the
// same grids this kernel writes. R16F and RG16F - what the fragment path
// actually uses for pressure and velocity - are not in the required
// image-format table at all, so binding the baseline's own grids as images
// would not be portable even on a device where it happens to work.
//
// Reads come through a usampler2D and texelFetch, not imageLoad, and only the
// write target is an image. Three reasons, in the order they matter: image
// UNIFORMS are the scarce resource here (GlCapabilities takes 4 as the floor
// that makes image load/store usable at all, and a kernel spending 3 of them on
// reads would sit one image from the edge of a conforming device); texelFetch
// goes through the texture cache, which is the path a driver has spent twenty
// years optimising for exactly this access pattern; and it is the convention
// SimGlsl already generates for the field sims, so the tier reads as one design.
// texelFetch returns the same bits imageLoad would - this is a bandwidth and
// budget choice, not a numerical one.
//
// The read textures must be NEAREST-complete. LINEAR on an integer texture
// leaves it incomplete and every fetch returns zero - silently, with no error
// flag and no black frame to notice, just a pressure field that is all zeros.
//
// writeonly is not decoration: ESSL 3.10 requires readonly or writeonly on every
// image whose format is not r32f/r32i/r32ui. Jacobi needs a separate read and
// write target anyway - updating in place would be Gauss-Seidel, a different
// iteration with a different convergence rate and a visibly different field.
uniform highp usampler2D uPressureSrc;   // texture unit 0
uniform highp usampler2D uDivergence;    // texture unit 1
layout(rgba32ui, binding = 0) writeonly uniform highp uimage2D uPressureDst;

uniform float uAlpha;    // -dx*dx, the same float the fragment pass is given
uniform int uIterations; // iterations to fuse in THIS dispatch, 1..HALO

// Two pressure planes plus the divergence tile. The planes live in ONE array
// addressed by a base offset rather than as float[2][N]: a dynamically indexed
// outer dimension is legal ESSL 3.10 but is exactly the construct that finds
// driver bugs, and a base offset compiles to the same address arithmetic
// everywhere. Divergence is loaded once because it does not change across the
// fused iterations - only pressure does.
shared float sPressure[2 * LOAD_CELLS];
shared float sDivergence[LOAD_CELLS];

int tileIndex(ivec2 local) {
    return local.y * LOAD_W + local.x;
}

// Neighbour fetch with the domain boundary RE-APPLIED.
//
// This is the one subtlety in the whole halo scheme, and it is where a
// hand-rolled version silently diverges from the fragment pass. Loading the
// ring with clamped global coordinates gives every ghost cell a copy of the
// edge texel, which is correct for the FIRST iteration and wrong for every one
// after it: the fragment pass re-clamps its uv on every iteration, so a ghost
// always mirrors the CURRENT edge value. A ghost that is instead iterated like
// an interior cell drifts away from the edge it is supposed to mirror, and the
// error walks inward one texel per iteration - which reads on screen as a
// pressure rim that the baseline does not have. Modelled against the reference
// iteration on a 48x32 grid, 8 iterations, 16x8 tiles and HALO 4: iterating the
// ghosts instead of re-deriving them puts 924 of 1536 texels wrong, worst case
// 0.19 on a field whose values are order 1. With the re-clamp the same model is
// bit-identical to the reference, every texel, at every grid size tested -
// including grids that are not a multiple of the tile.
//
// So ghosts are never iterated into meaning. Every read maps local -> global,
// clamps the global coordinate into the domain, and maps back. Ghost cells do
// get written by the loop below (they are inside the shrinking region), and
// those writes are simply never read.
//
// Invariant that makes the second mapping safe: this is only ever called with a
// local coordinate inside the loaded region, and the clamp only moves a
// coordinate INWARD, so the clamped global always lands back inside the loaded
// region. Both ends hold because every dispatched group has its origin inside
// the domain (the dispatch is a ceiling, so no group is entirely outside) and
// the region extends HALO past the tile on each side.
float pressureAt(int plane, ivec2 local, ivec2 loadOrigin, ivec2 size) {
    ivec2 g = clamp(loadOrigin + local, ivec2(0), size - ivec2(1));
    ivec2 inTile = g - loadOrigin;
    return sPressure[plane + inTile.y * LOAD_W + inTile.x];
}

void main() {
    // The domain, taken from the read texture: the ping-pong guarantees the write
    // target has the same extent, and the clamp below has to be the SOURCE grid's.
    ivec2 size = textureSize(uPressureSrc, 0);
    ivec2 lid = ivec2(gl_LocalInvocationID.xy);
    ivec2 tileOrigin = ivec2(gl_WorkGroupID.xy) * ivec2(TILE_W, TILE_H);
    ivec2 loadOrigin = tileOrigin - ivec2(HALO);

    // ---- cooperative load ---------------------------------------------------
    // Strided over the whole (tile + 2*HALO) region, so the loop is correct for
    // any local size the dispatch layer picks from the probed limits: with 16x8
    // invocations and HALO 4 each one loads three cells; with a 32x32 group most
    // load one and the rest load none. Nothing here assumes threads == cells,
    // which is the assumption that breaks the moment the halo exists.
    for (int y = lid.y; y < LOAD_H; y += TILE_H) {
        for (int x = lid.x; x < LOAD_W; x += TILE_W) {
            ivec2 g = clamp(loadOrigin + ivec2(x, y), ivec2(0), size - ivec2(1));
            int idx = y * LOAD_W + x;
            sPressure[idx] = uintBitsToFloat(texelFetch(uPressureSrc, g, 0).x);
            sDivergence[idx] = uintBitsToFloat(texelFetch(uDivergence, g, 0).x);
        }
    }

    // Two different guarantees, and the tile load needs both of them.
    //
    // barrier() orders EXECUTION: without it a fast invocation reads a cell that
    // a slow one has not written yet, and the tile is simply wrong - most often
    // in the corner groups, on one device, once every few hundred frames.
    //
    // memoryBarrierShared() orders MEMORY: it flushes the writes above out of
    // per-invocation write buffers so the other invocations can see them. Be
    // honest about this one - ES 3.1 says barrier() in a compute shader already
    // orders shared-variable accesses, so by the spec this call is redundant.
    // It stays because that guarantee is a single sentence for a driver to
    // under-implement, because the failure it would cause is intermittent
    // cross-invocation corruption (the hardest class of bug to attribute to its
    // cause), and because it costs nothing measurable next to the scheduling
    // barrier it sits beside. Writes, then the memory barrier, then the
    // execution barrier, then reads.
    memoryBarrierShared();
    barrier();

    // ---- the fused iterations ----------------------------------------------
    // After k iterations only the loaded region eroded by k is still valid, so
    // iteration k (0-based) writes the region eroded by k+1 and reads the region
    // eroded by k - which the previous iteration wrote in full. At k+1 == HALO
    // the region is exactly the tile. Running FEWER iterations than HALO is
    // always safe (the valid region is then larger than the tile), which is how
    // one program serves a remainder dispatch: uIterations carries how many are
    // wanted and the clamp makes an over-large request impossible rather than
    // silently stale. uIterations == 0 degrades to a copy, which is a harmless
    // no-op pass rather than a corrupt field.
    int cur = 0;
    int nxt = LOAD_CELLS;
    int iterations = clamp(uIterations, 0, HALO);
    for (int k = 0; k < iterations; ++k) {
        int m = k + 1;
        for (int y = lid.y + m; y < LOAD_H - m; y += TILE_H) {
            for (int x = lid.x + m; x < LOAD_W - m; x += TILE_W) {
                ivec2 l = ivec2(x, y);
                float L = pressureAt(cur, l - ivec2(1, 0), loadOrigin, size);
                float R = pressureAt(cur, l + ivec2(1, 0), loadOrigin, size);
                float T = pressureAt(cur, l + ivec2(0, 1), loadOrigin, size);
                float B = pressureAt(cur, l - ivec2(0, 1), loadOrigin, size);
                float div = sDivergence[tileIndex(l)];
                // Operand order copied verbatim from fluid_pressure_frag.glsl.
                // Floating-point addition is not associative, so (L + R + B + T)
                // and (L + R + T + B) differ in the last bit, and a solve that
                // differs in the last bit for 20 iterations a frame is a
                // different simulation by the time anyone looks at it.
                float p = (L + R + B + T + uAlpha * div) * 0.25;
#if GEODE_MATCH_HALF
                // The baseline stores pressure in R16F, so the fragment path
                // rounds to half precision after EVERY iteration. Fusing four
                // iterations in fp32 shared memory would silently produce a
                // better-converged field than the path it is supposed to
                // reproduce. Rounding here throws that accuracy away on purpose:
                // parity first, quality second, and the choice is visible in one
                // token instead of buried in a format decision. Set the token to
                // 0 once the two paths no longer need to be compared - it is a
                // deliberate, reviewable quality change, not a tuning knob.
                p = unpackHalf2x16(packHalf2x16(vec2(p, 0.0))).x;
#endif
                sPressure[nxt + tileIndex(l)] = p;
            }
        }
        // One barrier pair per iteration is sufficient, and it is worth saying
        // why, because "one looks too few" is how a redundant second one gets
        // added inside the loop. Each invocation reads the cur plane before it
        // writes the nxt plane, and this barrier sits after the writes; so by
        // the time any invocation starts the next iteration - where nxt becomes
        // the read plane and cur becomes the write plane - every invocation has
        // finished both its reads and its writes for this one. Nothing is ever
        // written while another invocation is still reading it. The double
        // buffer is what makes one barrier enough; updating a single plane in
        // place would need two, and would be Gauss-Seidel besides.
        memoryBarrierShared();
        barrier();
        int swap = cur;
        cur = nxt;
        nxt = swap;
    }

    // ---- the guarded store --------------------------------------------------
    // THE BOUNDS GUARD. A dispatch is rounded up to whole work groups, so the
    // last group on each axis carries invocations past the edge of the grid; an
    // unguarded imageStore there writes outside the image.
    //
    // It is a guard on the STORE and deliberately NOT an early return at the top
    // of main(). Every invocation above had to reach every barrier(): barrier()
    // is only defined when all invocations in the group execute it, and an
    // invocation that returned early never will. On real hardware that is not a
    // clean hang, it is a group that reads half-written shared memory - a
    // corrupt tile in the corner groups only, on some devices only, which is
    // about the worst bug shape this engine could ship. So: full participation
    // above, one bounds test at the bottom.
    ivec2 gid = ivec2(gl_GlobalInvocationID.xy);
    if (gid.x < size.x && gid.y < size.y) {
        float p = sPressure[cur + tileIndex(lid + ivec2(HALO))];
        // The fragment pass writes vec4(p, 0.0, 0.0, 1.0). floatBitsToUint(0.0)
        // is 0u exactly, so the packed zeros are the same zeros; the alpha of 1
        // is carried explicitly because the baseline's R16F target synthesises
        // it on read and the packed path has to write what it wants to read.
        imageStore(uPressureDst, gid, uvec4(floatBitsToUint(p), 0u, 0u, floatBitsToUint(1.0)));
    }
}

// WHERE THIS CAN STILL DIVERGE FROM THE FRAGMENT PASS
//
// 1. Divergence input precision. The baseline's divergence grid is R16F; this
//    kernel reads whatever the compute-tier divergence pass wrote into RGBA32UI,
//    which is fp32. Same numbers, different rounding, and no flag here can fix
//    it - it has to be fixed in the divergence kernel (round its result to half
//    under the same GEODE_MATCH_HALF token).
// 2. Denormals and overflow. packHalf2x16 flushes denormals and saturates to
//    Inf exactly as an R16F store does, so GEODE_MATCH_HALF = 1 matches there
//    too; with it 0 the compute path simply has more range, and the fragment
//    path's clamp of divergence to +/-60000 becomes the only thing keeping the
//    two in the same regime.
// 3. Iteration count. The fragment path runs pressureIterations exactly; this
//    one runs ceil(pressureIterations / HALO) dispatches. Give the last dispatch
//    the remainder through uIterations - do NOT round the total up to a multiple
//    of HALO, because a 21st iteration is a different pressure field, cheap
//    though it looks.
