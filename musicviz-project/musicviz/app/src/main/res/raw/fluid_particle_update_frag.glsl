#version 300 es
// Particle update kernel: v += (flow - v) * drag; p += v * dt; wrap.
// One fullscreen quad advances every particle (FLUID_SIM v2 section 8.2).
precision highp float;
// GLSL ES 3.00 defaults fragment sampler2D to LOWP (range [-2,2), ~8
// fraction bits). Half-float velocity/dye/pressure values far exceed
// that; on GPUs honoring sampler precision (Mali) every read clamped
// and quantized - the on-device "few pixels then black" root cause.
precision highp sampler2D;
in vec2 vUv;
uniform highp sampler2D uState;          // xy pos (sim), zw vel (sim/s)
uniform highp sampler2D uVelocityField;  // fluid velocity grid
uniform highp float uAspect;
uniform float uDt;
uniform float uDrag;
uniform float uFlowScale;          // grid velocity -> sim units per second
uniform float uRespawn;            // expected fraction of particles reborn per second
uniform float uTime;
out vec4 fragColor;
float hash(vec2 p) {
    p = fract(p * vec2(443.897, 441.423));
    p += dot(p, p.yx + 19.19);
    return fract((p.x + p.y) * p.x);
}
void main() {
    vec4 s = texture(uState, vUv);
    vec2 p = s.xy;
    vec2 v = s.zw;
    // Stochastic respawn: each particle is reborn at a fresh hashed position
    // with expected rate uRespawn/s. This continuously spawns new origin
    // points and dissolves any clustering (16F state quantisation included)
    // instead of letting the one-time seed distribution decay forever.
    if (uRespawn > 0.0 && hash(vUv + fract(uTime * 0.618)) < uRespawn * uDt) {
        float hx = hash(vUv * 1.37 + uTime);
        float hy = hash(vUv * 2.11 + uTime + 7.31);
        fragColor = vec4((hx * 2.0 - 1.0) * uAspect, hy * 2.0 - 1.0, 0.0, 0.0);
        return;
    }
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
