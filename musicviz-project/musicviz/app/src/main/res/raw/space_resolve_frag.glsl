#version 300 es
// Catmull-Rom upsample, implemented from the spline basis - published
// mathematics, no third-party listing copied. The five-tap arrangement is the
// standard consequence of the basis: two of the four taps per axis are
// adjacent and their bilinear-weighted midpoint is one hardware fetch, and the
// four corners of the resulting 3x3 carry so little weight that dropping them
// and renormalising is invisible.
precision highp float;
// GLSL ES 3.00 defaults fragment sampler2D to LOWP (range [-2,2), ~8 fraction
// bits) and this source is an RGBA16F HDR target, so the default would clamp
// and quantise every tap - the same defect fluid_copy_frag.glsl documents.
precision highp sampler2D;

in vec2 vUv;
uniform sampler2D uSource;
// Unsharp gain. 0 is a plain Catmull-Rom resolve; the upsample is a real
// resolution loss and a little acutance is what stops it reading as a blur.
uniform float uSharpen;
out vec4 fragColor;

void main() {
    vec2 texSize = vec2(textureSize(uSource, 0));
    vec2 samplePos = vUv * texSize;
    // Centre of the texel below-left of the sample, and the fraction into it.
    vec2 texPos1 = floor(samplePos - 0.5) + 0.5;
    vec2 f = samplePos - texPos1;

    // The Catmull-Rom cubic (tension 1/2) evaluated at f, per axis. w0 and w3
    // are the negative lobes: they are what sharpens, and what rings.
    vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
    vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
    vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
    vec2 w3 = f * f * (-0.5 + 0.5 * f);
    // w1 + w2 stays in [1, 1.125] over f in [0,1] - the two centre weights are
    // the whole of the kernel minus two small negative lobes - so the divide
    // below needs no guard.
    vec2 w12 = w1 + w2;
    vec2 offset12 = w2 / w12;

    vec2 texPos0 = (texPos1 - 1.0) / texSize;
    vec2 texPos3 = (texPos1 + 2.0) / texSize;
    vec2 texPos12 = (texPos1 + offset12) / texSize;

    vec4 c0 = texture(uSource, vec2(texPos12.x, texPos0.y));
    vec4 c1 = texture(uSource, vec2(texPos0.x, texPos12.y));
    vec4 c2 = texture(uSource, texPos12);
    vec4 c3 = texture(uSource, vec2(texPos3.x, texPos12.y));
    vec4 c4 = texture(uSource, vec2(texPos12.x, texPos3.y));

    float k0 = w12.x * w0.y;
    float k1 = w0.x * w12.y;
    float k2 = w12.x * w12.y;
    float k3 = w3.x * w12.y;
    float k4 = w12.x * w3.y;
    // The full 4x4 kernel sums to 1; this one is missing four corner weights,
    // so it is renormalised rather than left dark at high-frequency edges.
    vec4 col = (c0 * k0 + c1 * k1 + c2 * k2 + c3 * k3 + c4 * k4) / (k0 + k1 + k2 + k3 + k4);

    // Unsharp against the four cross taps we already paid for - no extra
    // fetches, and the texture unit is the budget that matters here.
    col += uSharpen * (col - 0.25 * (c0 + c1 + c3 + c4));
    // Then clamp into the neighbourhood the taps actually saw. Both the
    // unsharp and Catmull-Rom's own negative lobes can overshoot, and an
    // overshoot in an HDR buffer is not a subtle halo - it is a ring of pixels
    // several times brighter than anything in the source, which the composite
    // pass' bloom then finds and spreads.
    vec4 lo = min(min(min(min(c0, c1), c2), c3), c4);
    vec4 hi = max(max(max(max(c0, c1), c2), c3), c4);
    fragColor = clamp(col, lo, hi);
}
