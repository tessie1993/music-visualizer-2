#version 300 es
// Particle update kernel: v += (flow - v) * drag; p += v * dt; wrap +
// staggered lifetime respawn. One fullscreen quad advances every particle
// (FLUID_SIM v2 section 8.2).
precision highp float;
in vec2 vUv;
uniform highp sampler2D uState;          // xy pos (sim), zw vel (sim/s)
uniform highp sampler2D uVelocityField;  // fluid velocity grid
uniform highp float uAspect;
uniform float uDt;
uniform float uDrag;
uniform float uFlowScale;          // grid velocity -> sim units per second
uniform float uTime;               // sim-time seconds (for respawn phase)
out vec4 fragColor;

float hash(vec2 q) {
    q = fract(q * vec2(443.897, 441.423));
    q += dot(q, q.yx + 19.19);
    return fract((q.x + q.y) * q.x);
}

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
    // Staggered respawn: every particle recycles to a fresh hashed position
    // every ~8-16 s (phase-offset per particle so only a tiny fraction moves
    // per frame). Without this, particles slowly collapse onto streamlines
    // and stagnation points until the layer degenerates into clumped dots.
    float h = hash(vUv * 913.7);
    float life = mix(8.0, 16.0, hash(vUv * 517.3));
    if (fract(uTime / life + h) < uDt / life) {
        float gen = floor(uTime / life + h);
        p = vec2(
            (hash(vUv + gen * 0.173) * 2.0 - 1.0) * uAspect,
            hash(vUv.yx + gen * 0.377 + 3.7) * 2.0 - 1.0
        );
        v = vec2(0.0);
    }
    fragColor = vec4(p, v);
}
