package com.erenkng.mccamera.gl

/**
 * The two passes that turn a camera frame into a block mosaic.
 *
 * Pass 1 copies the external camera texture into an off-screen buffer that is
 * exactly `grid * 2^lod` pixels, rotated and centre-cropped to match the screen.
 * Its mipmap chain then holds, at level `lod`, the exact average colour of every
 * grid cell.
 *
 * Pass 2 reads that average per cell, looks the closest block up in a 32x32x32
 * colour cube and paints the block's texture into the cell.
 *
 * Pass 1 stays on ESSL 1.00 because external textures are universally supported
 * there; pass 2 needs ESSL 3.00 for `textureLod`.
 */
object Shaders {

    const val CAMERA_VERTEX = """
attribute vec2 aPos;
varying vec2 vUv;
void main() {
    vUv = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    const val CAMERA_FRAGMENT = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES sCam;
uniform mat4 uTexMatrix;
uniform mat3 uUvMatrix;
varying vec2 vUv;
void main() {
    // vUv is y-up; the camera pipeline thinks in y-down screen space.
    vec2 displayUv = vec2(vUv.x, 1.0 - vUv.y);
    vec2 bufferUv = (uUvMatrix * vec3(displayUv, 1.0)).xy;
    vec4 st = uTexMatrix * vec4(bufferUv.x, 1.0 - bufferUv.y, 0.0, 1.0);
    gl_FragColor = vec4(texture2D(sCam, st.xy).rgb, 1.0);
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
uniform vec2 uGrid;
uniform float uLod;
uniform float uTiles;
uniform float uShade;

in vec2 vUv;
out vec4 fragColor;

const float LUT_STEPS = 32.0;

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

    vec4 entry = texture(sLut, lutCoord(avg));
    vec2 tile = floor(entry.rg * 255.0 + 0.5);

    // Keep the sample well inside the tile so nearest filtering never bleeds
    // into the neighbouring block of the atlas.
    vec2 local = clamp(scaled - cell, 0.0008, 0.9992);
    vec2 atlasUv = (tile + vec2(local.x, 1.0 - local.y)) / uTiles;
    vec3 block = texture(sAtlas, atlasUv).rgb;

    float avgLuma = dot(avg, vec3(0.299, 0.587, 0.114));
    float shade = mix(1.0, clamp(avgLuma / max(entry.b, 0.05), 0.55, 1.7), uShade);

    fragColor = vec4(clamp(block * shade, 0.0, 1.0), 1.0);
}
"""
}
