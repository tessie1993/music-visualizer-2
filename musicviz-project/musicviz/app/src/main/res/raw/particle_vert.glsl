#version 300 es
// Instanced, velocity-stretched particle billboards for the CPU particle
// styles. One static unit quad is drawn once per particle
// (glDrawArraysInstanced), which buys three things point sprites could not:
//   * no GL_POINT_SIZE_MAX ceiling, so the aura in lib_particle_shade can be as
//     wide as it needs instead of being clipped to the sprite square;
//   * the quad orients along the particle's screen-space velocity and
//     stretches with its speed, so fast particles read as streaks;
//   * exact, continuous shape-space coordinates instead of gl_PointCoord,
//     which is what lets the fragment stage antialias with fwidth().
// The billboard and sub-pixel math live in lib_particle_common.glsl, included
// below, and are shared with the fluid styles' particle layer.
//#include lib_particle_common

// Static unit quad, [-1,1]^2 (attribute divisor 0).
layout(location = 0) in vec2 aCorner;
// Per-instance (divisor 1): position (NDC), size (px), hue [0,1), energy
// [0,1], velocity (NDC per second).
layout(location = 1) in vec2 aPos;
layout(location = 2) in float aSize;
layout(location = 3) in float aHue;
layout(location = 4) in float aEnergy;
layout(location = 5) in vec2 aVel;

uniform vec2 uViewport;   // render target size in px
uniform float uZoom;
uniform float uRotation;
uniform float uSize;
uniform float uStretch;      // seconds of travel folded into the streak length
uniform float uStretchMax;   // ceiling on the streak factor
// Squashes the SHORTER screen axis so a shape built in square units stays that
// shape on a non-square screen: a circle of radius r in the instance data is a
// circle on screen, not an ellipse stretched down the long axis. (1,1) - the
// default - leaves positions in raw NDC, which is what the field styles want:
// they fill the frame, and fitting them would leave bars top and bottom.
// ParticleSceneBase.aspectCorrected picks per style.
uniform vec2 uAspectFit;

out vec2 vShape;
out float vHue;
out float vEnergy;
out float vSeed;
out float vFade;
out float vStretch;

void main() {
    // Same column-major expression the old sprite path used, so the Rotation
    // slider still turns the scene the same way.
    mat2 rot = mat2(cos(uRotation), -sin(uRotation), sin(uRotation), cos(uRotation));
    // Rotate in square units, THEN fit: the other order shears the shape,
    // because a squashed frame rotated is not a rotated frame squashed.
    vec2 p = rot * aPos * uZoom * uAspectFit;
    vec2 vel = rot * aVel * uZoom * uAspectFit;

    // aSize is the sprite WIDTH in px, as it was when it fed gl_PointSize.
    float widthPx = aSize * uSize * max(uZoom, 0.25);
    vec2 held = ptRadiusFade(widthPx * 0.5);
    vFade = held.y;

    vec2 velPx = vel * uViewport * 0.5;
    vec2 offsetPx = ptBillboard(aCorner, velPx, held.x * PT_GLOW_EXTENT, uStretch, uStretchMax, vStretch);

    // Dead particles (size 0) are pushed outside clip space so the rasterizer
    // drops the whole quad rather than shading four discarded corners.
    vec2 ndc = p + offsetPx * 2.0 / max(uViewport, vec2(1.0));
    gl_Position = vec4(vFade > 0.0 ? ndc : vec2(4.0), 0.0, 1.0);

    vShape = aCorner;
    vHue = aHue;
    vEnergy = aEnergy;
    // Per-particle randomness for twinkle and dither; stable per instance.
    vSeed = fract(sin(float(gl_InstanceID) * 12.9898) * 43758.5453);
}
