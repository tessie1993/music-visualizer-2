#version 300 es
//#include lib_particle_common

layout(location = 0) in vec2 aCorner;
layout(location = 1) in vec2 aPos;
layout(location = 2) in float aSize;
layout(location = 3) in float aHue;
layout(location = 4) in float aEnergy;
layout(location = 5) in vec2 aVel;

uniform vec2 uViewport;
uniform float uZoom;
uniform float uRotation;
uniform float uSize;
uniform float uStretch;
uniform float uStretchMax;

out vec2 vShape;
out float vHue;
out float vEnergy;
out float vSeed;
out float vFade;
out float vStretch;

void main() {
    mat2 rot = mat2(cos(uRotation), -sin(uRotation), sin(uRotation), cos(uRotation));
    vec2 p = rot * aPos * uZoom;
    vec2 vel = rot * aVel * uZoom;

    float widthPx = aSize * uSize * max(uZoom, 0.25);
    vec2 held = ptRadiusFade(widthPx * 0.5);
    vFade = held.y;

    vec2 velPx = vel * uViewport * 0.5;
    vec2 offsetPx = ptBillboard(aCorner, velPx, held.x * PT_GLOW_EXTENT, uStretch, uStretchMax, vStretch);

    vec2 ndc = p + offsetPx * 2.0 / max(uViewport, vec2(1.0));
    gl_Position = vec4(vFade > 0.0 ? ndc : vec2(4.0), 0.0, 1.0);

    vShape = aCorner;
    vHue = aHue;
    vEnergy = aEnergy;
    vSeed = fract(sin(float(gl_InstanceID) * 12.9898) * 43758.5453);
}
