#version 300 es
// Per-particle attributes: position (NDC), size (px), hue [0,1), energy [0,1]
layout(location = 0) in vec2 aPos;
layout(location = 1) in float aSize;
layout(location = 2) in float aHue;
layout(location = 3) in float aEnergy;

uniform float uZoom;
uniform float uRotation;

out float vHue;
out float vEnergy;

void main() {
    float a = uRotation;
    vec2 p = mat2(cos(a), -sin(a), sin(a), cos(a)) * aPos * uZoom;
    gl_Position = vec4(p, 0.0, 1.0);
    gl_PointSize = aSize * max(uZoom, 0.25);
    vHue = aHue;
    vEnergy = aEnergy;
}
