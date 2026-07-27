#version 300 es
// Particle update kernel: v += (flow - v) * drag; p += v * dt; wrap.
// One fullscreen quad advances every particle (FLUID_SIM v2 section 8.2).
precision highp float;
in vec2 vUv;
uniform highp sampler2D uState;          // xy pos (sim), zw vel (sim/s)
uniform highp sampler2D uVelocityField;  // fluid velocity grid
uniform highp float uAspect;
uniform float uDt;
uniform float uDrag;
uniform float uFlowScale;          // grid velocity -> sim units per second
out vec4 fragColor;
void main() {
    vec4 s = texture(uState, vUv);
    vec2 p = s.xy;
    vec2 v = s.zw;
    // sim -> texel space for the field fetch
    vec2 uv = vec2(p.x / uAspect, p.y) * 0.5 + 0.5;
    vec2 flow = texture(uVelocityField, clamp(uv, 0.0, 1.0)).xy * uFlowScale;
    // Frame-rate-independent inertia: uDrag is the per-1/60s blend factor, so
    // trail character doesn't change between 60 Hz and 120 Hz displays.
    float k = 1.0 - pow(1.0 - uDrag, uDt * 60.0);
    v += (flow - v) * k;
    p += v * uDt;
    // Wrap at the domain edges so the field never empties.
    if (p.x >  uAspect) p.x -= 2.0 * uAspect;
    if (p.x < -uAspect) p.x += 2.0 * uAspect;
    if (p.y >  1.0) p.y -= 2.0;
    if (p.y < -1.0) p.y += 2.0;
    fragColor = vec4(p, v);
}
