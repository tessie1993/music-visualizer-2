#version 300 es
precision highp float;

// The Chladni plate itself, built from gl_VertexID: the grid has no vertex
// buffer at all, only an index buffer, so a resolution change costs one IBO
// rebuild and no per-frame upload. gl_VertexID under glDrawElements is the
// INDEX value, which is exactly the grid-vertex number this needs.
//
// Twin of CymaticsMath.modeHeight / surfaceHeight - if the plate formula
// changes here it changes there too (CymaticsMathTest pins the pair).

uniform int uGrid;           // cells per side; the vertex grid is uGrid+1 wide
uniform mat4 uMvp;
uniform vec3 uModes[8];      // (n, m, amplitude), amplitudes summing to <= 1
uniform int uModeCount;
uniform float uRelief;       // surface height in plate units
uniform float uVibration;    // whole-plate displacement factor, 1 = full relief
uniform float uFlat;         // 1 = the flat 2D sand view

out float vHeight;           // the FIGURE: envelope height, no vibration in it
out vec3 vNormal;
out vec3 vWorld;
out vec2 vPlate;

const float PI = 3.14159265359;

void main() {
    int verts = uGrid + 1;
    vec2 grid = vec2(float(gl_VertexID % verts), float(gl_VertexID / verts));
    vec2 p = grid / float(uGrid) * 2.0 - 1.0;

    // z(x,y) = cos(n PI x) cos(m PI y) - cos(m PI x) cos(n PI y), summed over
    // the ringing modes, with its analytic gradient for the surface normal.
    float h = 0.0;
    vec2 grad = vec2(0.0);
    for (int i = 0; i < 8; i++) {
        if (i >= uModeCount) break;
        float n = uModes[i].x;
        float m = uModes[i].y;
        float a = uModes[i].z;
        float cnx = cos(n * PI * p.x);
        float cmy = cos(m * PI * p.y);
        float cmx = cos(m * PI * p.x);
        float cny = cos(n * PI * p.y);
        float snx = sin(n * PI * p.x);
        float smy = sin(m * PI * p.y);
        float smx = sin(m * PI * p.x);
        float sny = sin(n * PI * p.y);
        h += a * (cnx * cmy - cmx * cny);
        grad.x += a * PI * (m * smx * cny - n * snx * cmy);
        grad.y += a * PI * (n * cmx * sny - m * cnx * smy);
    }

    vHeight = h;
    vPlate = p;

    // The whole surface oscillates together, in phase, exactly as a driven
    // plate does: the figure (vHeight) is untouched by it, which is why the
    // sand lines stay put while the metal between them moves.
    float lift = uRelief * uVibration * (1.0 - uFlat);
    vec3 world = vec3(p.x, h * lift, p.y);
    vec2 g = grad * lift;
    vNormal = normalize(vec3(-g.x, 1.0, -g.y));
    vWorld = world;
    gl_Position = uMvp * vec4(world, 1.0);
}
