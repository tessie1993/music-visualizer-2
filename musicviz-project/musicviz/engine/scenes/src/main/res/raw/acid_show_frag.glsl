#version 300 es
precision highp float;

// ACID - the present pass.
//
// The feedback state IS the picture; this pass only finishes it: a gentle
// contrast shape so long echo tails keep depth, the CRT dressing for the
// substyles that declare it (scanlines, aperture curvature), and a vignette.
// Nothing here feeds back, so it can be as nonlinear as it likes.

in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uState;
uniform vec2 uRes;
uniform float uScanline;  // 0..1 scanline mask strength
uniform float uCurve;     // 0..1 CRT barrel amount
uniform float uSat;       // saturation shape
uniform float uEnergy;

void main() {
    vec2 uv = vUv;
    float aspect = uRes.x / uRes.y;
    if (uCurve > 0.0) {
        vec2 c = uv - 0.5;
        float r2 = dot(c * vec2(aspect, 1.0), c * vec2(aspect, 1.0));
        uv = 0.5 + c * (1.0 + uCurve * 0.22 * r2);
        if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
            fragColor = vec4(0.0, 0.0, 0.0, 1.0);
            return;
        }
    }
    vec3 color = texture(uState, uv).rgb;

    // Gentle S-shape: lifts mids of the echo tails without crushing black.
    color = color * color * (3.0 - 2.0 * color);

    float luma = dot(color, vec3(0.299, 0.587, 0.114));
    color = mix(vec3(luma), color, uSat);

    if (uScanline > 0.0) {
        float line = 0.5 + 0.5 * cos(uv.y * uRes.y * 3.14159);
        color *= 1.0 - uScanline * 0.35 * line;
    }

    vec2 q = (uv - 0.5) * vec2(aspect, 1.0);
    color *= 1.0 - 0.4 * dot(q, q);

    fragColor = vec4(color, 1.0);
}
