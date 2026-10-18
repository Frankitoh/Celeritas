#version 330 core

#ifdef LEGACY
#extension GL_EXT_gpu_shader4 : require
#endif

#import <sodium:include/fog.glsl>

in vec4 v_Color;
in vec4 v_RdhFactor;
in vec2 v_QuadCoord;
in vec2 v_TexCoord;

#if defined(USE_FOG) && defined(CHUNK_FADE_IN_DURATION_MS) && CHUNK_FADE_IN_DURATION_MS > 0
in float v_ChunkAgeMs;
#endif

in float v_MaterialMipBias;
#ifdef USE_FRAGMENT_DISCARD
in float v_MaterialAlphaCutoff;
#endif

#if defined(USE_FOG_POSTMODERN)
in float v_SphericalFragDistance;
in float v_CylindricalFragDistance;
#elif defined(USE_FOG)
in float v_FragDistance;
#endif

uniform sampler2D u_BlockTex; // The block texture

#ifdef LUX_SHADOWS
in vec4 v_ShadowCoord;
in float v_SkyLight;
uniform sampler2D u_ShadowMap;
uniform float u_ShadowStrength;
uniform float u_ShadowBias;
uniform float u_ShadowTexel;

// Returns 1.0 when fully lit, 0.0 when fully shadowed (4-tap PCF with receiver-plane depth bias,
// fades out toward the map edge).
float _lux_shadow_factor() {
    vec3 p = v_ShadowCoord.xyz / v_ShadowCoord.w * 0.5 + 0.5;
    if (p.x <= 0.0 || p.x >= 1.0 || p.y <= 0.0 || p.y >= 1.0 || p.z >= 1.0) {
        return 1.0;
    }

    // How fast this surface's depth changes across the shadow map, from screen-space derivatives.
    vec3 dpx = dFdx(p);
    vec3 dpy = dFdy(p);
    float det = dpx.x * dpy.y - dpx.y * dpy.x;
    vec2 dzduv = vec2(0.0);
    if (abs(det) > 1.0e-12) {
        dzduv = vec2(dpx.z * dpy.y - dpx.y * dpy.z, dpx.x * dpy.z - dpx.z * dpy.x) / det;
    }

    // tan(angle between the surface normal and the light) = gradient * far / (2 * radius).
    // far / (2 * radius) is recovered from the uniforms: bias = 2 * texelWorld / far, texel = 1 / size.
    float depthScale = 2.0 * u_ShadowTexel / max(u_ShadowBias, 1.0e-6);
    float tanT = length(dzduv) * depthScale;
    float cosT = inversesqrt(1.0 + tanT * tanT);

    // Surfaces almost parallel to the light cannot be compared reliably against a shadow map (they would show
    // up as dark noisy blotches). Leave those to vanilla's per-face shading.
    float facing = smoothstep(0.12, 0.35, cosT);
    if (facing <= 0.0) {
        return 1.0;
    }

    // Derivatives mix neighbouring faces at triangle edges; keep the gradient sane there.
    float maxSlope = 6.0 / depthScale;
    dzduv = clamp(dzduv, vec2(-maxSlope), vec2(maxSlope));

    float t = u_ShadowTexel * 0.5;
    vec2 o0 = vec2(-t, -t);
    vec2 o1 = vec2( t, -t);
    vec2 o2 = vec2(-t,  t);
    vec2 o3 = vec2( t,  t);
    float lit = 0.0;
    lit += step(p.z + dot(dzduv, o0) - u_ShadowBias, texture(u_ShadowMap, p.xy + o0).r);
    lit += step(p.z + dot(dzduv, o1) - u_ShadowBias, texture(u_ShadowMap, p.xy + o1).r);
    lit += step(p.z + dot(dzduv, o2) - u_ShadowBias, texture(u_ShadowMap, p.xy + o2).r);
    lit += step(p.z + dot(dzduv, o3) - u_ShadowBias, texture(u_ShadowMap, p.xy + o3).r);
    lit *= 0.25;

    float edge = max(abs(p.x - 0.5), abs(p.y - 0.5)) * 2.0;
    float fade = 1.0 - smoothstep(0.75, 1.0, edge);
    return mix(1.0, lit, fade * facing);
}
#endif

uniform int u_FogShape;
uniform vec4 u_FogColor; // The color of the shader fog

#ifdef USE_FOG_SMOOTH
uniform float u_FogStart; // The starting position of the shader fog
uniform float u_FogEnd; // The ending position of the shader fog
#endif

#ifdef USE_FOG_POSTMODERN
uniform float u_RenderDistFogStart;
uniform float u_RenderDistFogEnd;
uniform float u_EnvFogStart;
uniform float u_EnvFogEnd;
#endif

#if defined(USE_FOG_EXP) || defined(USE_FOG_EXP2)
uniform float u_FogDensity; // The density of the shader fog
#endif

#ifndef LEGACY
out vec4 fragColor; // The output fragment for the color framebuffer
#else
#define fragColor gl_FragColor
#endif

void main() {
    vec4 diffuseColor = texture(u_BlockTex, v_TexCoord, v_MaterialMipBias);

#ifdef USE_FRAGMENT_DISCARD
    if (diffuseColor.a < v_MaterialAlphaCutoff) {
        discard;
    }
#endif

    vec4 m_color = v_Color;
    // Apply correction factor to make the resulting color very close to what true bilinear interpolation would obtain
    // min(x, y) * (1 - max(x, y)) == min(x, y) - (x * y)
    float correctionWeight = min(v_QuadCoord.x, v_QuadCoord.y)
            - (v_QuadCoord.x * v_QuadCoord.y);
    m_color += v_RdhFactor * correctionWeight;

#ifdef USE_VANILLA_COLOR_FORMAT
    // Apply per-vertex color. AO shade is applied ahead of time on the CPU.
    diffuseColor *= m_color;
#else
    // Apply per-vertex color
    diffuseColor.rgb *= m_color.rgb;

    // Apply ambient occlusion "shade"
    diffuseColor.rgb *= m_color.a;
#endif

#ifdef LUX_SHADOWS
    if (u_ShadowStrength > 0.001) {
        // Only darken where vanilla sky light reaches, so caves are not darkened twice.
        float luxShadow = _lux_shadow_factor();
        float luxAmount = (1.0 - luxShadow) * u_ShadowStrength * v_SkyLight * v_SkyLight;
        diffuseColor.rgb *= (1.0 - luxAmount);
    }
#endif

#ifdef USE_FOG

#if defined(CHUNK_FADE_IN_DURATION_MS) && CHUNK_FADE_IN_DURATION_MS > 0
    // Make chunk fade in over a short duration
    diffuseColor = vec4(mix(u_FogColor.rgb, diffuseColor.rgb, (clamp(v_ChunkAgeMs, 0, CHUNK_FADE_IN_DURATION_MS) / CHUNK_FADE_IN_DURATION_MS)), diffuseColor.a);
#endif

#if defined(USE_FOG_POSTMODERN)
    float fogValue = max(_linearFogValue(v_CylindricalFragDistance, u_RenderDistFogStart, u_RenderDistFogEnd),
                         _linearFogValue(v_SphericalFragDistance, u_EnvFogStart, u_EnvFogEnd));

    fragColor = vec4(mix(diffuseColor.rgb, u_FogColor.rgb, fogValue * u_FogColor.a), diffuseColor.a);
#else // Legacy fog
    float fragDistance;
    if (u_FogShape == FOG_SHAPE_PLANAR) {
        fragDistance = gl_FragCoord.z / gl_FragCoord.w;
    } else {
        fragDistance = v_FragDistance;
    }
#if defined(USE_FOG_EXP2)
    fragColor = _exp2Fog(diffuseColor, fragDistance, u_FogColor, u_FogDensity);
#elif defined(USE_FOG_EXP)
    fragColor = _expFog(diffuseColor, fragDistance, u_FogColor, u_FogDensity);
#elif defined(USE_FOG_SMOOTH)
    fragColor = _linearFog(diffuseColor, fragDistance, u_FogColor, u_FogStart, u_FogEnd);
#endif
#endif

#else // No fog
    fragColor = diffuseColor;
#endif
}
