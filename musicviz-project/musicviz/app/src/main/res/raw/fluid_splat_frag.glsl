#version 300 es
// Capsule (swept-segment) injection with velocity blending, per FLUID_SIM v2
// section 7 (clean-room from spec; distance-to-segment is elementary geometry).
precision highp float;
in vec2 vUv;
in vec2 vSim;
uniform sampler2D uTarget;
uniform vec2 uPrev;       // sim space
uniform vec2 uCur;        // sim space
uniform float uRadius;    // sim space
uniform vec3 uValue;      // mode 0: (targetVel.xy in grid units, 0); mode 1: dye rgb
uniform int uMode;
out vec4 fragColor;

float segDist(vec2 a, vec2 b, vec2 p, out float fp) {
    vec2 ab = b - a;
    float len2 = dot(ab, ab);
    if (len2 < 1e-8) { fp = 0.0; return length(p - a); }
    fp = clamp(dot(p - a, ab) / len2, 0.0, 1.0);
    return length(p - (a + ab * fp));
}

void main() {
    vec4 base = texture(uTarget, vUv);
    float fp;
    float l = segDist(uPrev, uCur, vSim, fp);
    float taper = 1.0 - fp * 0.6;
    float m = exp(-l / max(uRadius, 1e-4)) * taper;
    if (uMode == 0) {
        vec2 v = base.xy + (uValue.xy - base.xy) * m;
        fragColor = vec4(v, 0.0, 1.0);
    } else {
        fragColor = vec4(base.rgb + uValue * m, 1.0);
    }
}
