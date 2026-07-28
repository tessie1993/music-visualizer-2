#version 300 es
// GL_POINTS render: static VBO of texel coords; state fetched in the vertex
// stage (vertex texture fetch, core ES3). FLUID_SIM v2 section 8.1.
precision highp float;
layout(location = 0) in vec2 aTexel;
uniform highp sampler2D uState;
uniform highp float uAspect;
uniform highp float uPointScale;
uniform highp float uTime;
out highp float vSpeed;
out highp float vLife;

// MUST mirror fluid_particle_update_frag's hash/lifetime exactly: the fade
// envelope here hides the respawn that kernel performs.
float hash(vec2 q) {
    q = fract(q * vec2(443.897, 441.423));
    q += dot(q, q.yx + 19.19);
    return fract((q.x + q.y) * q.x);
}

void main() {
    vec4 s = texture(uState, aTexel);
    vSpeed = length(s.zw);
    // Life-phase fade: particles are born and die INVISIBLY (brightness
    // ramps 0->1 after respawn and 1->0 before it), so the staggered
    // recycling reads as smoke continuously forming and dissolving instead
    // of dots teleporting.
    float h = hash(aTexel * 913.7);
    float life = mix(8.0, 16.0, hash(aTexel * 517.3));
    float phase = fract(uTime / life + h);
    vLife = smoothstep(0.0, 0.12, phase) * (1.0 - smoothstep(0.85, 1.0, phase));
    gl_Position = vec4(s.x / uAspect, s.y, 0.0, 1.0);
    // Hard 4px cap: additive overdraw cost grows with the SQUARE of point
    // size, and converging particles at 8px collapsed the frame rate.
    gl_PointSize = clamp(uPointScale * (0.8 + vSpeed * 1.2), 1.0, 4.0);
}
