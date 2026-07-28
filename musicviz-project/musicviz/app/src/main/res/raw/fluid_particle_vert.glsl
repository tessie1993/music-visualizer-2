#version 300 es
// GL_POINTS render: static VBO of texel coords; state fetched in the vertex
// stage (vertex texture fetch, core ES3). FLUID_SIM v2 section 8.1.
precision highp float;
layout(location = 0) in vec2 aTexel;
uniform highp sampler2D uState;
uniform highp float uAspect;
uniform highp float uPointScale;
out highp float vSpeed;
void main() {
    vec4 s = texture(uState, aTexel);
    vSpeed = length(s.zw);
    gl_Position = vec4(s.x / uAspect, s.y, 0.0, 1.0);
    // Hard 4px cap: additive overdraw cost grows with the SQUARE of point
    // size, and converging particles at 8px collapsed the frame rate.
    gl_PointSize = clamp(uPointScale * (0.8 + vSpeed * 1.2), 1.0, 4.0);
}
