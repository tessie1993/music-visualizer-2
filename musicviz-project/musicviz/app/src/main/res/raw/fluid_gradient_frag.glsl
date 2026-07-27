#version 300 es
// Ported from WebGL-Fluid-Simulation - MIT License, (c) 2017 Pavel Dobryakov
precision mediump float;
in vec2 vUv; in vec2 vL; in vec2 vR; in vec2 vT; in vec2 vB;
uniform sampler2D uPressure;
uniform sampler2D uVelocity;
uniform float uHalfRdx;
uniform highp vec2 uInvRes;
out vec4 fragColor;
float sampleP(vec2 c) {
    vec2 cc = clamp(c, uInvRes * 0.5, 1.0 - uInvRes * 0.5);
    return texture(uPressure, cc).x;
}
void main() {
    float L = sampleP(vL);
    float R = sampleP(vR);
    float T = sampleP(vT);
    float B = sampleP(vB);
    vec2 v = texture(uVelocity, vUv).xy;
    fragColor = vec4(v - uHalfRdx * vec2(R - L, T - B), 0.0, 1.0);
}
