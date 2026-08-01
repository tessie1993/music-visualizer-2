#version 300 es
// Instanced, velocity-stretched particle billboards.
//
// Replaces the old GL_POINTS sprite path. One static unit quad is drawn once
// per particle (glDrawArraysInstanced), which buys three things point sprites
// could not:
//   * no GL_POINT_SIZE_MAX ceiling, so particle_frag's soft halo can be as
//     wide as it needs instead of being clipped to the sprite square;
//   * the quad is oriented along the particle's screen-space velocity and
//     stretched by its speed - the standard "stretched billboard" motion trick
//     (NVIDIA, Stupid OpenGL Shader Tricks, GDC 2003) - so fast particles read
//     as streaks rather than strobing dots;
//   * shape-space coordinates are exact and continuous instead of
//     gl_PointCoord, which is what lets the fragment stage antialias its SDFs
//     with fwidth().
precision highp float;

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
uniform float uStretch;   // seconds of travel folded into the streak length

out vec2 vShape;
out float vHue;
out float vEnergy;
out float vSeed;
out float vFade;
out float vStretch;

// Quad half-extent as a multiple of the shape radius: the SDF body lives
// inside |vShape| <= SHAPE_R (particle_frag) and the rest of the quad is
// margin for the glow falloff.
const float GLOW_EXTENT = 2.6;
// Sub-pixel sprites alias and strobe. The starfield fix is to hold the radius
// at roughly a pixel and dim by the area given up, so shrinking particles fade
// out smoothly instead of flickering.
const float MIN_RADIUS_PX = 0.85;

void main() {
    // Same column-major expression the old sprite path used, so the Rotation
    // slider still turns the scene the same way.
    mat2 rot = mat2(cos(uRotation), -sin(uRotation), sin(uRotation), cos(uRotation));
    vec2 p = rot * aPos * uZoom;
    vec2 vel = rot * aVel * uZoom;

    // aSize is the sprite WIDTH in px, as it was when it fed gl_PointSize.
    float widthPx = aSize * uSize * max(uZoom, 0.25);
    float radiusPx = widthPx * 0.5;
    float held = max(radiusPx, MIN_RADIUS_PX);
    vFade = radiusPx > 0.0 ? min(1.0, (radiusPx * radiusPx) / (held * held)) : 0.0;

    // Screen-space velocity -> streak. dir is the long axis and its
    // perpendicular the short one; under ~1 px/s there is no meaningful
    // direction, so the quad stays axis-aligned and the sprite reads round.
    vec2 velPx = vel * uViewport * 0.5;
    float speedPx = length(velPx);
    float stretch = 1.0 + clamp(speedPx * uStretch, 0.0, 1.0);
    vec2 dir = speedPx > 1.0 ? velPx / speedPx : vec2(1.0, 0.0);
    float quadPx = held * GLOW_EXTENT;
    vec2 offsetPx = dir * (aCorner.x * quadPx * stretch) + vec2(-dir.y, dir.x) * (aCorner.y * quadPx);

    // Dead particles (size 0) are pushed outside clip space so the rasterizer
    // drops the whole quad rather than shading four discarded corners.
    vec2 ndc = p + offsetPx * 2.0 / max(uViewport, vec2(1.0));
    gl_Position = vec4(vFade > 0.0 ? ndc : vec2(4.0), 0.0, 1.0);

    vShape = aCorner;
    vStretch = stretch;
    vHue = aHue;
    vEnergy = aEnergy;
    // Per-particle randomness for twinkle and dither; stable per instance.
    vSeed = fract(sin(float(gl_InstanceID) * 12.9898) * 43758.5453);
}
