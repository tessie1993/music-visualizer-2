#version 300 es
// GL_POINTS render: static VBO of texel coords; state fetched in the vertex
// stage (vertex texture fetch, core ES3). Rebuilt for the lifecycle layer:
// age drives a fade-in / fade-out envelope so births and recycles are soft,
// and the emitter index rides along for per-spawn-point coloring.
precision highp float;
// GLSL ES 3.00 defaults fragment sampler2D to LOWP (range [-2,2), ~8
// fraction bits). Half-float state values far exceed that; on GPUs
// honoring sampler precision (Mali) every read clamps and quantizes.
precision highp sampler2D;
layout(location = 0) in vec2 aTexel;
uniform highp sampler2D uState;
uniform highp sampler2D uMeta;
uniform highp float uAspect;
uniform highp float uPointScale;
out highp float vSpeed;
out highp float vFade;
out highp float vEmitter;
out highp float vSeed;
void main() {
    vec4 s = texture(uState, aTexel);
    vec4 m = texture(uMeta, aTexel);
    vSpeed = length(s.zw);
    float age = m.x;
    float ttl = max(m.y, 1e-3);
    // Soft envelope: 0.35 s fade-in, last 20% of life fades out.
    vFade = smoothstep(0.0, 0.35, age) * (1.0 - smoothstep(ttl * 0.8, ttl, age));
    vEmitter = m.z;
    vSeed = m.w;
    gl_Position = vec4(s.x / uAspect, s.y, 0.0, 1.0);
    gl_PointSize = clamp(uPointScale * (0.8 + vSpeed * 1.6), 1.0, 8.0);
}
