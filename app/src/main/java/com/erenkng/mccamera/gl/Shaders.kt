package com.erenkng.mccamera.gl

/**
 * The two passes that turn a camera frame into a block mosaic.
 *
 * Pass 1 copies the source (camera or an imported photo) into an off-screen
 * buffer that is exactly `grid * 2^lod` pixels, rotated, centre-cropped and
 * colour-graded. Its mipmap chain then holds, at level `lod`, the exact average
 * colour of every grid cell.
 *
 * Pass 2 reads that average per cell, looks the closest block up in a 32x32x32
 * colour cube and paints the block into the cell.
 *
 * Pass 1 stays on ESSL 1.00 because external textures are universally supported
 * there; pass 2 needs ESSL 3.00 for `textureLod`.
 */
object Shaders {

    /** Shared colour grading applied before the averaging, so it survives it. */
    private const val GRADE_ESSL1 = """
uniform float uBrightness;
uniform float uContrast;
uniform float uSaturation;

vec3 grade(vec3 c) {
    c = c + uBrightness;
    c = (c - 0.5) * uContrast + 0.5;
    float l = dot(c, vec3(0.299, 0.587, 0.114));
    c = mix(vec3(l), c, uSaturation);
    return clamp(c, 0.0, 1.0);
}
"""

    const val SOURCE_VERTEX = """
attribute vec2 aPos;
varying vec2 vUv;
void main() {
    vUv = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    /** Pass 1 reading the live camera (external OES texture). */
    val CAMERA_FRAGMENT = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES sCam;
uniform mat4 uTexMatrix;
uniform mat3 uUvMatrix;
varying vec2 vUv;
$GRADE_ESSL1
void main() {
    // vUv is y-up; the camera pipeline thinks in y-down screen space.
    vec2 displayUv = vec2(vUv.x, 1.0 - vUv.y);
    vec2 bufferUv = (uUvMatrix * vec3(displayUv, 1.0)).xy;
    vec4 st = uTexMatrix * vec4(bufferUv.x, 1.0 - bufferUv.y, 0.0, 1.0);
    gl_FragColor = vec4(grade(texture2D(sCam, st.xy).rgb), 1.0);
}
"""

    /** Pass 1 reading a photo the user imported from the gallery. */
    val STILL_FRAGMENT = """
precision mediump float;
uniform sampler2D sCam;
uniform mat4 uTexMatrix;
uniform mat3 uUvMatrix;
varying vec2 vUv;
$GRADE_ESSL1
void main() {
    vec2 displayUv = vec2(vUv.x, 1.0 - vUv.y);
    vec2 bufferUv = (uUvMatrix * vec3(displayUv, 1.0)).xy;
    vec4 st = uTexMatrix * vec4(bufferUv.x, 1.0 - bufferUv.y, 0.0, 1.0);
    gl_FragColor = vec4(grade(texture2D(sCam, st.xy).rgb), 1.0);
}
"""

    const val MOSAIC_VERTEX = """#version 300 es
in vec2 aPos;
out vec2 vUv;
void main() {
    vUv = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    const val MOSAIC_FRAGMENT = """#version 300 es
precision highp float;

uniform sampler2D sDown;
uniform sampler2D sLut;
uniform sampler2D sAtlas;
uniform sampler2D sPalette;

uniform vec2 uGrid;
uniform float uLod;
uniform float uTiles;
uniform float uPaletteSize;

uniform float uShade;     // 0..1, how far the block is pushed toward the scene's brightness
uniform float uDither;    // 0..1, ordered dithering amount
uniform float uBevel;     // 0..1, fake Minecraft lighting on the cell edges
uniform float uOutline;   // 0..1, dark grid line between cells
uniform int uMode;        // 0 blocks, 1 map art (flat colour), 2 raw pixels

in vec2 vUv;
out vec4 fragColor;

const float LUT_STEPS = 32.0;

// Bayer 4x4, the classic ordered dither kernel. One offset per cell is exactly
// what a palette this coarse needs: neighbouring cells pick different blocks and
// the eye blends them back into the original colour.
const int BAYER[16] = int[16](0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5);

vec2 lutCoord(vec3 c) {
    vec3 q = floor(clamp(c, 0.0, 1.0) * (LUT_STEPS - 1.0) + 0.5);
    float u = (q.b * LUT_STEPS + q.r + 0.5) / (LUT_STEPS * LUT_STEPS);
    float v = (q.g + 0.5) / LUT_STEPS;
    return vec2(u, v);
}

void main() {
    vec2 scaled = vUv * uGrid;
    vec2 cell = floor(scaled);
    vec3 avg = textureLod(sDown, (cell + 0.5) / uGrid, uLod).rgb;

    if (uMode == 2) {
        fragColor = vec4(avg, 1.0);
        return;
    }

    vec3 wanted = avg;
    if (uDither > 0.0) {
        int bx = int(mod(cell.x, 4.0));
        int by = int(mod(cell.y, 4.0));
        float t = (float(BAYER[by * 4 + bx]) + 0.5) / 16.0 - 0.5;
        wanted = clamp(wanted + t * uDither, 0.0, 1.0);
    }

    vec4 entry = texture(sLut, lutCoord(wanted));
    vec2 tile = floor(entry.rg * 255.0 + 0.5);

    // Keep the sample well inside the tile so nearest filtering never bleeds
    // into the neighbouring block of the atlas.
    vec2 local = clamp(scaled - cell, 0.0008, 0.9992);

    vec3 block;
    if (uMode == 1) {
        float index = tile.y * uTiles + tile.x;
        block = texture(sPalette, vec2((index + 0.5) / uPaletteSize, 0.5)).rgb;
    } else {
        vec2 atlasUv = (tile + vec2(local.x, 1.0 - local.y)) / uTiles;
        block = texture(sAtlas, atlasUv).rgb;
    }

    float avgLuma = dot(avg, vec3(0.299, 0.587, 0.114));
    float shade = mix(1.0, clamp(avgLuma / max(entry.b, 0.05), 0.55, 1.7), uShade);
    block *= shade;

    if (uBevel > 0.0) {
        // Light from the top-left, shadow bottom-right: reads as a raised cube.
        float lit = step(0.88, local.y) + step(local.x, 0.12);
        float dark = step(local.y, 0.12) + step(0.88, local.x);
        block *= 1.0 + uBevel * 0.20 * (min(lit, 1.0) - min(dark, 1.0));
    }

    if (uOutline > 0.0) {
        float w = 1.0 / 16.0;
        float edge = min(1.0, step(local.x, w) + step(1.0 - w, local.x) +
                              step(local.y, w) + step(1.0 - w, local.y));
        block = mix(block, block * 0.45, uOutline * edge);
    }

    fragColor = vec4(clamp(block, 0.0, 1.0), 1.0);
}
"""
}
