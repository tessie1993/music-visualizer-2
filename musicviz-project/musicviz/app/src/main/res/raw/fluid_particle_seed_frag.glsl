#version 300 es
// Particle state seeding: position (xy, sim space) hashed from texel coord,
// velocity (zw) zero. State-in-texture GPGPU per FLUID_SIM v2 section 8.
precision highp float;
in vec2 vUv;
uniform highp float uAspect;
out vec4 fragColor;
float hash(vec2 p) {
    p = fract(p * vec2(443.897, 441.423));
    p += dot(p, p.yx + 19.19);
    return fract((p.x + p.y) * p.x);
}
void main() {
    float hx = hash(vUv);
    float hy = hash(vUv + 7.31);
    fragColor = vec4((hx * 2.0 - 1.0) * uAspect, hy * 2.0 - 1.0, 0.0, 0.0);
}
