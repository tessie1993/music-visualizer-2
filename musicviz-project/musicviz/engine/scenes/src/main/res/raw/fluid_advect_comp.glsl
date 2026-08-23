#version 310 es
precision highp float;
precision highp int;
layout(local_size_x = %%LOCAL_SIZE_X%%, local_size_y = %%LOCAL_SIZE_Y%%) in;

// FLUID / ADVECTION - semi-Lagrangian back-trace, as a compute kernel.
//
// The compute-tier twin of fluid_advect_frag.glsl, line for line. Both passes
// FluidSim.step makes to that shader come here: the velocity self-advection
// (source grid == velocity grid) and the dye advection (a 4x finer source grid
// sampling a coarse velocity field). One kernel serves both, exactly as one
// fragment program does.
//
// There is no clever restructuring available here and none is wanted: the pass
// is a gather, not a scatter, and its arithmetic is already minimal. What the
// compute version removes is the rasterization it never needed - no fullscreen
// triangle, no FBO bind, no tile resolve and store per step, no varying
// interpolation for a value that is just the texel centre. On a tiler that
// per-pass overhead is the reason the fluid costs what it costs, not the ALU
// inside it (§6.3: budget bandwidth, not ALU).
//
// ============================ THE FILTERING TRAP =============================
//
// READ THIS BEFORE CHANGING ANYTHING BELOW. RGBA32UI state has NO texture
// filtering. None. An integer texture with GL_LINEAR is INCOMPLETE - not
// approximate, incomplete, every fetch returns zero - so the only fetch
// available is texelFetch at integer texel coordinates, and the only image
// access is imageLoad/imageStore, which never filtered in the first place.
//
// That is the price of the format that lets ES 3.0 and ES 3.1 hold bit-identical
// state (§6.3: EXT_color_buffer_float is not core in ES 3.0, RGBA32UI is). Every
// interpolation this pass performs is therefore written out by hand, and this is
// precisely the pass where the two paths would silently drift if it were not.
//
// Two fetches in the fragment version interpolate, and they interpolate for
// different reasons:
//
//   (a) bilerp() of the SOURCE field. Already manual in the fragment shader -
//       it samples the four texel CENTRES and mixes them itself. Ported here
//       one-for-one, with clamp-to-edge expressed as a clamp of the texel index
//       (the fragment path gets it from GL_CLAMP_TO_EDGE on the texture).
//
//   (b) texture(uVelocity, vUv) of the VELOCITY field. This one is hardware
//       filtering, and it is invisible in the fragment source because it is
//       sampler state, not code: FluidSim binds a GL_LINEAR sampler object to
//       unit 0 for the dye pass and nothing for the velocity pass. So the same
//       line of GLSL means "interpolate the coarse velocity grid at this fine
//       dye texel" in one call and "read this exact texel" in the other. A
//       compute port that dropped the filtering would advect the dye with a
//       blocky velocity field - the dye grid is 4x finer, so the artefact is
//       4x4 stair-steps crawling through every filament, and it would look like
//       a bad tuning value rather than a missing sampler.
//
//       One manual bilinear fetch ALMOST covers both, because sampling exactly
//       at a texel centre makes the fractional weights zero and returns that
//       texel. Almost: `uv / inv - 0.5` does not land exactly on an integer once
//       the grid width is not a power of two, because the round trip through the
//       float32 reciprocal uniform is not exact. Measured on the real arithmetic:
//       232 of 1080 texel centres on a 1080-wide grid, 39 of 320, 33 of 213 land
//       one ulp off, which makes floor() choose the NEIGHBOURING texel and give
//       the intended one a weight of 0.99999988. For the dye pass that is
//       harmless - bilinear interpolation is continuous in st, so a one-ulp
//       disagreement costs one ulp of result. For the velocity self-advect it is
//       not, because the pass it has to match is NEAREST, and NEAREST is a
//       DISCONTINUOUS function of the coordinate: it floors uv*size with no half
//       texel shift, lands half a texel from the nearest boundary, and returns
//       texel x every time.
//
//       So the fetch below branches on whether the velocity grid is the
//       destination grid, and reads the exact texel when it is. That branch IS
//       the sampler state - the thing the fragment source could not show you -
//       written down where it can be reviewed. It is uniform across the
//       dispatch, so it costs nothing.
//
// SUBSTITUTION CONTRACT (plain string replacement by the dispatch layer):
//   %%LOCAL_SIZE_X%%  work-group width  (default 8)
//   %%LOCAL_SIZE_Y%%  work-group height (default 8)
//   %%MATCH_HALF%%    1 or 0, see GEODE_MATCH_HALF below (default 1)
// The tokens are not valid GLSL, so a forgotten substitution fails loudly at
// compile time instead of quietly running at somebody's default.

#define GEODE_MATCH_HALF %%MATCH_HALF%%

// Reads through a usampler2D, the write target as the only image: image uniforms
// are the scarce resource at the ES 3.1 floor of four, texelFetch goes through
// the texture cache (this kernel's source gather is a back-traced, deliberately
// incoherent access - the one that most wants that cache), and it matches the
// convention SimGlsl generates for the field sims. Same bits either way.
//
// Both read textures must be NEAREST-complete: LINEAR on an integer texture
// leaves it incomplete and every fetch returns zero, with no error to notice.
uniform highp usampler2D uSource;    // texture unit 0 - the state being stepped
uniform highp usampler2D uVelocity;  // texture unit 1
layout(rgba32ui, binding = 0) writeonly uniform highp uimage2D uDst;

// These two are the fragment pass's own uniforms and must be given the SAME
// floats it is given (1f/width, 1f/height of the respective grid). It would be
// more accurate to derive texel space from textureSize() and skip the reciprocal
// entirely - and that is exactly why we do not. uv / (1/w) is not w * uv in
// floating point, and this kernel's job is to reproduce the baseline, not to
// improve on it in ways nobody can diff.
uniform vec2 uSrcInvRes;  // texel size of uSource (the bilerp grid)
uniform vec2 uVelInvRes;  // texel size of the VELOCITY grid (back-trace scale)
uniform float uDt;
uniform float uRdx;       // 1/cellSize
uniform vec3 uDecay;      // per-channel (1 + dissipation*dt); velocity uses .xxx

// Samplers can legally be function parameters in ESSL, but a parameter would buy
// nothing here: there are exactly two source textures and they are known at
// compile time. Two named fetchers cost twelve duplicated lines and make each
// call site say which grid it is reading, which is the thing that actually goes
// wrong in this pass (the back-trace is scaled by the VELOCITY texel size while
// the fetch is on the SOURCE grid). The duplication is deliberate; keep the two
// bodies identical if either changes.
vec4 loadVelocityTexel(ivec2 c) {
    ivec2 s = textureSize(uVelocity, 0);
    return uintBitsToFloat(texelFetch(uVelocity, clamp(c, ivec2(0), s - ivec2(1)), 0));
}

vec4 loadSourceTexel(ivec2 c) {
    ivec2 s = textureSize(uSource, 0);
    return uintBitsToFloat(texelFetch(uSource, clamp(c, ivec2(0), s - ivec2(1)), 0));
}

// bilerp(), ported from fluid_advect_frag.glsl. The fragment version computes
// the four sample points as (i + 0.5)*inv, (i + 1.5)*inv ... and lets the
// texture unit turn each one back into a texel index; here the index IS the
// index, so the +0.5 round trip disappears and clamp-to-edge is applied to the
// integer coordinate. Same four texels, same two mixes, same weights.
vec4 bilerpVelocity(vec2 uv, vec2 inv) {
    vec2 st = uv / inv - 0.5;
    ivec2 i = ivec2(floor(st));
    vec2 f = fract(st);
    vec4 a = loadVelocityTexel(i);
    vec4 b = loadVelocityTexel(i + ivec2(1, 0));
    vec4 c = loadVelocityTexel(i + ivec2(0, 1));
    vec4 d = loadVelocityTexel(i + ivec2(1, 1));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

vec4 bilerpSource(vec2 uv, vec2 inv) {
    vec2 st = uv / inv - 0.5;
    ivec2 i = ivec2(floor(st));
    vec2 f = fract(st);
    vec4 a = loadSourceTexel(i);
    vec4 b = loadSourceTexel(i + ivec2(1, 0));
    vec4 c = loadSourceTexel(i + ivec2(0, 1));
    vec4 d = loadSourceTexel(i + ivec2(1, 1));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

// Round a packed texel to what an RGBA16F / RG16F target would have stored, so
// the compute path and the baseline hold the same bits rather than merely the
// same intent. See fluid_pressure_comp.glsl for the full argument; the short
// version is that parity is a property worth paying precision for while the two
// paths must be comparable, and the payment is one token.
vec4 roundToHalf(vec4 v) {
    return vec4(unpackHalf2x16(packHalf2x16(v.xy)), unpackHalf2x16(packHalf2x16(v.zw)));
}

void main() {
    ivec2 gid = ivec2(gl_GlobalInvocationID.xy);
    ivec2 dstSize = imageSize(uDst);
    // THE BOUNDS GUARD. The dispatch is rounded up to whole work groups, so the
    // last group on each axis carries invocations past the edge of the grid and
    // an unguarded store there writes outside the image. An early return is only
    // safe here because this kernel contains no barrier() - see the pressure
    // kernel, where the same guard has to be a guarded store instead.
    if (gid.x >= dstSize.x || gid.y >= dstSize.y) return;

    // The fragment path gets vUv from the rasterizer interpolating the vertex
    // shader's aPosition * 0.5 + 0.5. At a fragment centre that is (i+0.5)/size,
    // which is what this computes - but by a different route, so the two can
    // differ in the last bit. Documented, not fixable: there is no way to ask a
    // compute invocation for "whatever the rasterizer would have produced".
    vec2 vUv = (vec2(gid) + 0.5) / vec2(dstSize);

    // The velocity fetch, and the one branch in this kernel. FluidSim calls the
    // fragment version twice with different sampler state: nothing bound for the
    // velocity self-advect (NEAREST, same grid, exact texel) and a GL_LINEAR
    // sampler object for the dye advect (a 4x finer destination sampling a coarse
    // velocity field). The condition is derived from the image sizes rather than
    // passed as a uniform, because it is not an opinion - it is what those two
    // call sites structurally are, and a uniform is one more thing to set wrongly.
    // See the FILTERING TRAP banner for why the same-grid case cannot just fall
    // out of the bilinear path.
    ivec2 velSize = textureSize(uVelocity, 0);
    vec2 vel;
    if (all(equal(velSize, dstSize))) {
        vel = uintBitsToFloat(texelFetch(uVelocity, gid, 0)).xy;
    } else {
        vel = bilerpVelocity(vUv, uVelInvRes).xy;
    }
    // Self-heal: a NaN/Inf velocity texel (driver quirk, transient overflow)
    // must not smear through the back-trace and latch the field black.
    if (isnan(vel.x) || isnan(vel.y) || isinf(vel.x) || isinf(vel.y)) vel = vec2(0.0);
    // The trace displacement is scaled by the VELOCITY grid's texel size, not
    // the source's: with uSrcInvRes here the dye (4x finer grid) would advect
    // at 1/4 of the fluid's actual speed and visibly lag its own vortices.
    // Multiplication order is copied verbatim - ((uDt*uRdx)*vel)*uVelInvRes -
    // because reassociating it changes the last bit of every trace.
    vec2 traced = vUv - uDt * uRdx * vel * uVelInvRes;
    vec4 s = bilerpSource(traced, uSrcInvRes);
    // A division, not a multiply by the reciprocal, because the fragment pass
    // divides. Same reason as the operand order above.
    s.rgb /= uDecay;
    bvec4 nan = isnan(s);
    bvec4 inf = isinf(s);
    if (any(nan) || any(inf)) s = vec4(0.0);

    vec4 result = vec4(s.rgb, s.a);
#if GEODE_MATCH_HALF
    result = roundToHalf(result);
#endif
    // Alpha is carried through rather than forced to 1: the baseline's RG16F
    // velocity target synthesises alpha 1 on read and its RGBA16F dye target
    // stores a real alpha, and s.a already holds whichever of those the source
    // image was written with. Every fluid compute kernel writes w = 1.0 for a
    // grid the baseline holds in RG16F or R16F, which is what keeps that
    // synthesised channel true in the packed path.
    imageStore(uDst, gid, floatBitsToUint(result));
}

// WHERE THIS CAN STILL DIVERGE FROM THE FRAGMENT PASS
//
// 1. The velocity fetch for the dye pass. The fragment path's GL_LINEAR sampler
//    interpolates with implementation-defined fixed-point subtexel weights (the
//    engine's own filtersLinearly probe accepts anything in [0.30, 0.70] at the
//    midpoint for exactly this reason). This kernel interpolates in fp32. The
//    compute result is the more correct one; it is not the identical one, and no
//    flag can make it identical, because the quantity being reproduced is not
//    specified to a bit anywhere in the ES spec.
// 2. The same effect, smaller, inside the fragment path's own bilerp(): it
//    samples texel centres through a GL_LINEAR-filtered dye texture, so an ulp
//    of error in (i + 0.5) * inv leaks a fraction of the neighbouring texel into
//    what is meant to be an exact fetch. The compute version's integer texel
//    reads have no such leak. Again: better, not identical.
// 3. vUv reconstruction, as noted at the top of main().
// 4. The same-grid branch is taken whenever the dye grid happens to equal the
//    velocity grid (dyeRes == simRes), where the fragment path still has its
//    GL_LINEAR sampler bound and so pays that sampler's subtexel error at a texel
//    centre. The branch gives the exact texel instead. Smaller error, not the
//    same error - and the direction is towards the value both passes intend.
// 5. mix(a, b, 0.0) is a exactly under IEEE - except when b is Inf, where
//    (b - a) * 0.0 is NaN. The fragment path would have returned a. Both end at
//    the same place: the isnan/isinf sanitiser after the fetch zeroes the texel either
//    way, which is the behaviour the baseline shows for an Inf in the field too.
