#version 300 es
// Ported from WebGL-Fluid-Simulation - MIT License, (c) 2017 Pavel Dobryakov
precision mediump float;
in vec2 vUv; in vec2 vL; in vec2 vR; in vec2 vT; in vec2 vB;
uniform sampler2D uVelocity;
uniform float uHalfRdx;
out vec4 fragColor;
void main() {
    float L = texture(uVelocity, vL).y;
    float R = texture(uVelocity, vR).y;
    float T = texture(uVelocity, vT).x;
    float B = texture(uVelocity, vB).x;
    fragColor = vec4(uHalfRdx * ((R - L) - (T - B)), 0.0, 0.0, 1.0);
}
