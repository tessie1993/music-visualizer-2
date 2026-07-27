#version 300 es
// Ported from WebGL-Fluid-Simulation - MIT License, (c) 2017 Pavel Dobryakov
// Soft-knee HDR prefilter: keeps only the energy above threshold, with a
// quadratic knee so the cutoff doesn't band.
precision mediump float;
in vec2 vUv;
uniform sampler2D uTexture;
uniform vec3 uCurve;      // (threshold - knee, 2*knee, 0.25/knee)
uniform float uThreshold;
out vec4 fragColor;
void main() {
    vec3 c = texture(uTexture, vUv).rgb;
    float br = max(c.r, max(c.g, c.b));
    float rq = clamp(br - uCurve.x, 0.0, uCurve.y);
    rq = uCurve.z * rq * rq;
    c *= max(rq, br - uThreshold) / max(br, 0.0001);
    fragColor = vec4(c, 0.0);
}
