#version 300 es
precision highp float;

// Oscilloscope beam geometry: one quad per waveform segment, expanded here so
// the fragment stage can integrate a Gaussian beam along it.
//
// Ported from woscope (MIT, Igor Null and Chad von Nau) - see
// THIRD_PARTY_NOTICES. woscope draws from a vertex buffer of sample pairs; this
// reads the waveform from a texture and derives everything from gl_VertexID, so
// a frame uploads one 1D texture instead of rebuilding a VBO.

/** Waveform samples in the red channel, one per texel, -1..1. */
uniform sampler2D uWave;
/** Number of segments drawn, i.e. samples - 1 (sweep) or samples (XY). */
uniform int uCount;
/** 0 = time sweep (x is time), 1 = XY (two channels against each other). */
uniform float uMode;
/** Beam half-width in normalized units - the sigma the fragment integrates. */
uniform float uSigma;
/** Output aspect, so the beam stays round on a non-square screen. */
uniform float uAspect;
/** Vertical gain of the trace. */
uniform float uGain;
/** Quarter-cycle offset used to fake the second channel; see CymaticsScene. */
uniform int uPhaseOffset;

/** Position relative to the segment: x along it, y across it. */
out vec2 vLocal;
/** Length of this segment in the same units, for the dwell normalization. */
out float vLen;
/** 0..1 along the trace, so the fragment can fade the tail. */
out float vAge;

vec2 samplePoint(int index) {
    int n = max(uCount, 1);
    float w = texelFetch(uWave, ivec2(index % textureSize(uWave, 0).x, 0), 0).r;
    if (uMode < 0.5) {
        // Time sweep: x walks the screen, y is the sample.
        return vec2(float(index) / float(n) * 2.0 - 1.0, w * uGain);
    }
    // XY: this sample against one a quarter cycle away.
    int other = (index + uPhaseOffset) % textureSize(uWave, 0).x;
    float w2 = texelFetch(uWave, ivec2(other, 0), 0).r;
    return vec2(w * uGain, w2 * uGain);
}

void main() {
    int seg = gl_VertexID / 6;
    int corner = gl_VertexID % 6;
    vec2 p0 = samplePoint(seg);
    vec2 p1 = samplePoint(seg + 1);

    // Aspect-correct BEFORE measuring the segment: a beam that is round on
    // screen has to be round in the space the integral is computed in.
    vec2 a = vec2(p0.x * uAspect, p0.y);
    vec2 b = vec2(p1.x * uAspect, p1.y);
    vec2 dir = b - a;
    float len = length(dir);
    vec2 tangent = len > 1e-6 ? dir / len : vec2(1.0, 0.0);
    vec2 normal = vec2(-tangent.y, tangent.x);

    // Quad padded by 3 sigma each way: past that the Gaussian is under a
    // thousandth and the extra fill rate buys nothing.
    float pad = uSigma * 3.0;
    // Two triangles, corners in order (-,-) (+,-) (-,+) / (+,-) (+,+) (-,+).
    float along = (corner == 1 || corner == 3 || corner == 4) ? len + pad : -pad;
    float across = (corner == 2 || corner == 4 || corner == 5) ? pad : -pad;

    vec2 pos = a + tangent * along + normal * across;
    vLocal = vec2(along, across);
    vLen = len;
    vAge = float(seg) / float(max(uCount, 1));
    gl_Position = vec4(pos.x / uAspect, pos.y, 0.0, 1.0);
}
