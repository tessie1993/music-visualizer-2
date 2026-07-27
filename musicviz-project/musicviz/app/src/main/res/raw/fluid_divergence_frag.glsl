#version 300 es
// Free-slip velocity boundaries via reflected sampling, per FLUID_SIM v2
// section 6.2 (GPU Gems ch.38 boundary treatment, common practice).
precision mediump float;
in vec2 vUv; in vec2 vL; in vec2 vR; in vec2 vT; in vec2 vB;
uniform sampler2D uVelocity;
uniform float uHalfRdx;
uniform highp vec2 uInvRes;
out vec4 fragColor;
vec2 sampleVel(vec2 c) {
    vec2 mul = vec2(1.0);
    vec2 cc = c;
    if (cc.x < 0.0) { cc.x = uInvRes.x * 0.5; mul.x = -1.0; }
    if (cc.x > 1.0) { cc.x = 1.0 - uInvRes.x * 0.5; mul.x = -1.0; }
    if (cc.y < 0.0) { cc.y = uInvRes.y * 0.5; mul.y = -1.0; }
    if (cc.y > 1.0) { cc.y = 1.0 - uInvRes.y * 0.5; mul.y = -1.0; }
    return texture(uVelocity, cc).xy * mul;
}
void main() {
    float L = sampleVel(vL).x;
    float R = sampleVel(vR).x;
    float T = sampleVel(vT).y;
    float B = sampleVel(vB).y;
    fragColor = vec4(uHalfRdx * ((R - L) + (T - B)), 0.0, 0.0, 1.0);
}
