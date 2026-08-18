#version 300 es
precision highp float;

// SILK - the present pass.
//
// The sim texture holds three band lanes of dye (r bass, g mid, b treble).
// Each lane gets its own point on the user's palette - base hue for the bass
// body, offsets for mid and treble filaments - then the sum is tone-mapped.
// Styles that declare mirror folds recompose the field kaleidoscopically
// here, in the PRESENT pass only, so the simulation itself stays unfolded
// and un-mirrored (folding the sim would double-advect the seam).

in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uField;
uniform vec2 uRes;        // output size, for aspect
uniform float uBaseHue;   // palette identity, turns
uniform float uHueSpan;   // palette span, turns
uniform float uExposure;
uniform int uFold;        // mirror folds; 0 = none
uniform float uFoldPhase; // slow fold rotation, radians
uniform float uEnergy;    // overall level, lifts the floor glow

const float TAU = 6.2831853;

vec3 hsv2rgb(vec3 c) {
    vec3 p = abs(fract(c.xxx + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);
    return c.z * mix(vec3(1.0), clamp(p - 1.0, 0.0, 1.0), c.y);
}

vec2 foldUv(vec2 uv) {
    if (uFold <= 0) return uv;
    float aspect = uRes.x / uRes.y;
    vec2 q = (uv - 0.5) * vec2(aspect, 1.0);
    float a = atan(q.y, q.x) + uFoldPhase;
    float r = length(q);
    float seg = TAU / float(uFold);
    a = abs(mod(a, seg) - seg * 0.5);
    q = r * vec2(cos(a), sin(a));
    return q / vec2(aspect, 1.0) + 0.5;
}

void main() {
    vec3 lanes = texture(uField, foldUv(vUv)).rgb;

    float span = uHueSpan * 0.18;
    vec3 cBass = hsv2rgb(vec3(fract(uBaseHue), 0.85, 1.0));
    vec3 cMid = hsv2rgb(vec3(fract(uBaseHue + span), 0.7, 1.0));
    vec3 cTreb = hsv2rgb(vec3(fract(uBaseHue + 2.0 * span), 0.45, 1.0));

    vec3 hdr = cBass * lanes.r + cMid * lanes.g + cTreb * lanes.b;

    // Tone map: additive lanes are HDR by construction; exp rolloff keeps
    // crossings luminous without clipping to one white blob.
    vec3 color = 1.0 - exp(-hdr * uExposure);

    // A whisper of ambient depth so silence is a deep field, not a void.
    float aspect = uRes.x / uRes.y;
    vec2 q = (vUv - 0.5) * vec2(aspect, 1.0);
    float vig = 1.0 - 0.45 * dot(q, q);
    color += cBass * 0.015 * (0.4 + uEnergy) * vig;
    color *= vig;

    fragColor = vec4(color, 1.0);
}
