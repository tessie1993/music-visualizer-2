#version 300 es
// Emissive shading for the CPU particle styles. The look itself lives in
// lib_particle_shade.glsl (included below) and is shared with the fluid
// styles' particle layer; this stage only wires the uniforms to it.
//#include lib_particle_common
//#include lib_particle_shade

in vec2 vShape;
in float vHue;
in float vEnergy;
in float vSeed;
in float vFade;
in float vStretch;
out vec4 fragColor;

uniform float uSat;
uniform float uBright;
uniform float uContrast;
uniform float uGamma;
uniform float uShape;
uniform float uGlow;   // aura weight; rides the Bloom slider
uniform float uTime;

void main() {
    if (vFade <= 0.0) discard;
    vec4 c =
        ptShade(
            vShape,
            vHue,
            vEnergy,
            vSeed,
            vFade,
            vStretch,
            uShape,
            uSat,
            uBright,
            uContrast,
            uGamma,
            uGlow,
            uTime,
            gl_FragCoord.xy
        );
    if (c.a <= 0.0 && dot(c.rgb, c.rgb) <= 0.0) discard;
    // Invert is owned by the composite pass (uPostInvert) so it is applied
    // exactly once to the whole frame.
    fragColor = c;
}
