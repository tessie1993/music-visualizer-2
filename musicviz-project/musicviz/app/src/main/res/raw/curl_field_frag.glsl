#version 300 es
// Curl-noise velocity field per Bridson et al. "Curl-Noise for Procedural
// Fluid Flow" (SIGGRAPH 2007): v = (dPsi/dy, -dPsi/dx) of an FBM value-noise
// potential - divergence-free by construction, so particles stream and
// swirl without clumping (docs/ORGANIC_MOTION.md quick-win 2).
precision highp float;
in vec2 vUv;
in vec2 vSim;
uniform float uTime;     // pre-scaled noise time (mids drive the rate)
uniform float uFreq;     // base spatial frequency
uniform float uDetail;   // third-octave gain (treble adds fine turbulence)
uniform float uAmp;      // output speed, sim units/s
out vec4 fragColor;

float hash(vec3 p) {
    p = fract(p * 0.3183099 + vec3(0.1, 0.2, 0.3));
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}

float vnoise(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float n000 = hash(i);
    float n100 = hash(i + vec3(1, 0, 0));
    float n010 = hash(i + vec3(0, 1, 0));
    float n110 = hash(i + vec3(1, 1, 0));
    float n001 = hash(i + vec3(0, 0, 1));
    float n101 = hash(i + vec3(1, 0, 1));
    float n011 = hash(i + vec3(0, 1, 1));
    float n111 = hash(i + vec3(1, 1, 1));
    return mix(
        mix(mix(n000, n100, f.x), mix(n010, n110, f.x), f.y),
        mix(mix(n001, n101, f.x), mix(n011, n111, f.x), f.y),
        f.z
    );
}

float psi(vec2 p) {
    vec3 q = vec3(p * uFreq, uTime);
    float v = vnoise(q) * 0.625;
    v += vnoise(q * 2.02 + 11.3) * 0.25;
    v += vnoise(q * 4.05 + 29.7) * 0.125 * uDetail;
    return v;
}

void main() {
    const float e = 0.02;
    float dpdx = psi(vSim + vec2(e, 0.0)) - psi(vSim - vec2(e, 0.0));
    float dpdy = psi(vSim + vec2(0.0, e)) - psi(vSim - vec2(0.0, e));
    vec2 v = vec2(dpdy, -dpdx) / (2.0 * e);
    fragColor = vec4(v * uAmp, 0.0, 1.0);
}
