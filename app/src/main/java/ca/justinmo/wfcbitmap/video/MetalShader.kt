package ca.justinmo.wfcbitmap.video

/**
 * GLSL ES 3.00 sources for the metal look.
 *
 * The fragment shader is the whole material. It turns the drawing's luminance into a height field,
 * differentiates it into a tangent-space normal (the tangent frame is the screen plane, so the normal is
 * already in surface space), and feeds that normal to:
 *
 *  - a Cook-Torrance key light with an anisotropic GGX distribution, Smith height-correlated masking and
 *    Schlick Fresnel, attenuated over distance;
 *  - a prefiltered procedural studio environment, sampled along the reflection vector with a roughness
 *    LOD and a split-sum BRDF lookup table; and
 *  - an optional parallax offset that makes the raised cells occlude the ones behind them.
 *
 * Every term is evaluated in linear light. The albedo texture is uploaded as sRGB so the hardware decodes
 * it, and the fragment shader writes an ACES-tone-mapped, gamma-encoded result for the 8-bit target.
 *
 * The moving key is expressed in the same surface space as the normals, which is why its highlight
 * travels across the bevels instead of sliding over a flat tint.
 */
internal object MetalShader {

  /**
   * A full-screen triangle generated from `gl_VertexID`; no vertex buffer is needed.
   *
   * `vScreen` is the framebuffer position in 0..1 with y pointing down, which is also the orientation of
   * an Android bitmap and of the read-back pixels. The renderer flips the rows once when it converts
   * `glReadPixels`' bottom-up RGBA into an ARGB bitmap.
   */
  const val VERTEX = """#version 300 es
precision highp float;
out vec2 vScreen;
void main() {
  vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
  // The viewport covers p in 0..1, not 0..2: the triangle is oversized so the clipped part is exactly
  // the square. Using p * 0.5 here would sample a quarter of the texture stretched over the frame.
  vScreen = vec2(p.x, 1.0 - p.y);
}
"""

  /** The embossed metal surface. */
  const val FRAGMENT = """#version 300 es
precision highp float;
precision highp int;

in vec2 vScreen;
out vec4 fragColor;

uniform sampler2D uAlbedo;
uniform sampler2D uEnvironment;
uniform sampler2D uBrdf;

uniform vec2 uTexSize;
uniform vec2 uUvOffset;
uniform vec2 uUvScale;
uniform float uViewportAspect;
uniform float uImageAspect;
uniform float uWrapX;
uniform float uWrapY;
uniform float uStepTexels;

uniform float uEmboss;
uniform float uMetallic;
uniform float uRoughness;
uniform float uTextureTint;
uniform vec3 uBaseColor;
uniform vec3 uLightColor;
uniform float uLightIntensity;
uniform vec3 uLightPosition;

uniform float uEnvironmentIntensity;
uniform float uEnvironmentRotation;
uniform float uParallax;
uniform float uAnisotropy;
uniform float uBrushAngle;
uniform float uExposure;
uniform float uCameraDistance;

const float PI = 3.14159265359;
const float INV_PI = 0.31830988618;

vec2 wrapped(vec2 p) {
  if (uWrapX > 0.5) p.x = fract(p.x); else p.x = clamp(p.x, 0.0, 1.0);
  if (uWrapY > 0.5) p.y = fract(p.y); else p.y = clamp(p.y, 0.0, 1.0);
  return p;
}

vec3 readAlbedo(vec2 p) {
  return texture(uAlbedo, wrapped(p)).rgb;
}

/** The height field: the drawing's luminance, gently lifted out of the dark end. */
float readHeight(vec2 p) {
  vec3 c = readAlbedo(p);
  float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
  return clamp(pow(l, 0.85), 0.0, 1.0);
}

vec2 environmentUv(vec3 d) {
  return vec2(atan(d.z, d.x) * (0.5 / PI) + 0.5,
              acos(clamp(d.y, -1.0, 1.0)) * INV_PI);
}

vec3 readEnvironment(vec3 d, float lod) {
  return textureLod(uEnvironment, environmentUv(d), lod).rgb;
}

vec3 rotateY(vec3 d, float a) {
  float c = cos(a);
  float s = sin(a);
  return vec3(c * d.x + s * d.z, d.y, -s * d.x + c * d.z);
}

void main() {
  // Fit the image inside the viewport without stretching it. For an export both aspects are equal and
  // this is a no-op; for the live preview it letterboxes or pillarboxes the drawing.
  vec2 uv;
  if (uViewportAspect > uImageAspect) {
    float s = uImageAspect / uViewportAspect;
    uv = vec2((vScreen.x - 0.5) / s + 0.5, vScreen.y);
  } else {
    float s = uViewportAspect / uImageAspect;
    uv = vec2(vScreen.x, (vScreen.y - 0.5) / s + 0.5);
  }
  if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
    fragColor = vec4(0.0, 0.0, 0.0, 1.0);
    return;
  }
  uv = uUvOffset + uv * uUvScale;

  float aspect = max(uImageAspect, 0.05);
  vec2 plane0 = vec2((uv.x * 2.0 - 1.0) * aspect, 1.0 - uv.y * 2.0);
  vec3 V0 = normalize(vec3(0.0, 0.0, uCameraDistance) - vec3(plane0, 0.0));

  // Parallax: sample the height a few times and slide the lookup against the view ray. Defaults off,
  // because a strong offset swims on a pixel-art cell grid; it is there for photographic textures.
  vec2 puv = uv;
  if (uParallax > 0.0001) {
    for (int i = 0; i < 3; i++) {
      float h = readHeight(puv);
      puv = uv + (V0.xy / max(V0.z, 0.25)) * (h - 0.5) * uParallax * 0.18;
    }
    uv = puv;
  }

  vec3 albedo = readAlbedo(uv);
  float height = readHeight(uv);

  // Central differences across roughly one drawn cell. The step is in texels, not normalised UV, so the
  // bevel keeps the same width when the source resolution changes. Wrapping is done by `wrapped`, which
  // is what keeps the gradient continuous across a looping panorama's seam.
  vec2 texel = 1.0 / max(uTexSize, vec2(1.0));
  // The bevel is deliberately narrower than a drawn cell: a cell-wide slope leaves no flat top and the
  // relief reads as a soft blur. A 35% bevel keeps a machined plate flat in the middle and catches the
  // light only on its edge, which is what makes the picture look engraved instead of smudged.
  float stepPx = clamp(uStepTexels * 0.35, 1.0, 6.0);
  vec2 d = texel * stepPx;
  float hR = readHeight(uv + vec2(d.x, 0.0));
  float hL = readHeight(uv - vec2(d.x, 0.0));
  float hD = readHeight(uv + vec2(0.0, d.y));
  float hU = readHeight(uv - vec2(0.0, d.y));

  float slopeScale = uEmboss * 3.4 / stepPx;
  float gx = (hR - hL) * 0.5 * slopeScale / max(aspect, 0.25);
  float gy = (hD - hU) * 0.5 * slopeScale;
  vec3 N = normalize(vec3(-gx, gy, 1.0));

  float brushAngle = radians(uBrushAngle);
  vec2 brushDir = vec2(cos(brushAngle), sin(brushAngle));
  if (uAnisotropy > 0.01) {
    // Fine grooves perpendicular to the brush, at a fixed number of cycles per source texel so a
    // different texture resolution does not turn into a different material.
    float streak = sin(dot(uv * uTexSize, brushDir) * 0.22) * uAnisotropy * 0.06;
    N = normalize(N + vec3(-brushDir.y, brushDir.x, 0.0) * streak);
  }

  vec2 plane = vec2((uv.x * 2.0 - 1.0) * aspect, 1.0 - uv.y * 2.0);
  vec3 P = vec3(plane, 0.0);
  vec3 V = normalize(vec3(0.0, 0.0, uCameraDistance) - P);
  vec3 L = normalize(uLightPosition - P);
  vec3 H = normalize(L + V);

  float NdotL = max(dot(N, L), 0.0);
  float NdotV = max(dot(N, V), 0.001);
  float NdotH = max(dot(N, H), 0.0);
  float VdotH = max(dot(V, H), 0.0);

  float a = max(uRoughness * uRoughness, 0.0001);
  float at = max(a * (1.0 + uAnisotropy), 0.0001);
  float ab = max(a * (1.0 - 0.9 * uAnisotropy), 0.0001);
  vec3 T = normalize(vec3(brushDir, 0.0) - N * dot(vec3(brushDir, 0.0), N));
  vec3 B = normalize(cross(N, T));
  vec3 Ht = vec3(dot(H, T), dot(H, B), dot(H, N));
  float dd = Ht.x * Ht.x / (at * at) + Ht.y * Ht.y / (ab * ab) + Ht.z * Ht.z;
  float D = 1.0 / max(PI * at * ab * dd * dd, 0.000001);
  // A point light on a polished mirror is a sub-pixel dot. The key here is a studio soft-box, so the
  // direct term gets a small broad lobe: the tight GGX highlight still rides on top of it, but the
  // highlight is wide enough to see travel across a bevel at low roughness.
  float soft = max(uRoughness, 0.10);
  float softAlpha = soft * soft;
  float ddSoft = (Ht.x * Ht.x + Ht.y * Ht.y) / softAlpha + Ht.z * Ht.z;
  float dSoft = 1.0 / max(PI * softAlpha * ddSoft * ddSoft, 0.000001);
  D = mix(D, dSoft, 0.35);

  float alphaG = sqrt(at * ab);
  float k = alphaG + 1.0;
  k = k * k / 8.0;
  float Gv = NdotV / (NdotV * (1.0 - k) + k);
  float Gl = NdotL / (NdotL * (1.0 - k) + k);
  float G = Gv * Gl;

  vec3 metalTint = mix(uBaseColor, albedo, uTextureTint);
  vec3 F0 = mix(vec3(0.04), metalTint, uMetallic);
  vec3 F = F0 + (vec3(1.0) - F0) * pow(1.0 - VdotH, 5.0);
  vec3 specular = (D * G * F) / max(4.0 * NdotL * NdotV, 0.0001);
  vec3 kd = (vec3(1.0) - F) * (1.0 - uMetallic);

  vec3 toLight = uLightPosition - P;
  float attenuation = 1.0 / (1.0 + 0.18 * dot(toLight, toLight));
  vec3 radiance = uLightColor * uLightIntensity * attenuation;
  vec3 direct = (kd * albedo * INV_PI + specular) * radiance * NdotL;

  // Environment reflection: split-sum, with the cubemap's mip chain standing in for the prefiltered
  // radiance and a small GGX importance-sampled LUT for the BRDF integral.
  vec3 R = reflect(-V, N);
  float envLod = clamp(uRoughness * 8.0, 0.0, 8.5);
  vec3 prefiltered = readEnvironment(rotateY(R, uEnvironmentRotation), envLod);
  if (uAnisotropy > 0.01) {
    float spread = uRoughness * uAnisotropy * 0.5;
    vec3 e1 = readEnvironment(rotateY(normalize(R + T * spread), uEnvironmentRotation), envLod);
    vec3 e2 = readEnvironment(rotateY(normalize(R - T * spread), uEnvironmentRotation), envLod);
    prefiltered = (prefiltered + e1 + e2) * (1.0 / 3.0);
  }
  vec2 brdf = texture(uBrdf, vec2(NdotV, uRoughness)).rg;
  vec3 envSpecular = prefiltered * (F0 * brdf.x + brdf.y);
  vec3 envDiffuse = readEnvironment(rotateY(N, uEnvironmentRotation), 8.0) * albedo * (1.0 - uMetallic);

  // A raised cell sees more of the studio than a recessed one; a small occlusion term is what stops the
  // valleys from looking like they are floating in front of the plate.
  float occlusion = mix(0.70, 1.0, height);
  vec3 environment = (envSpecular + envDiffuse) * uEnvironmentIntensity * occlusion;

  vec3 color = (direct + environment) * uExposure;
  color = (color * (2.51 * color + 0.03)) / (color * (2.43 * color + 0.59) + 0.14);
  color = pow(clamp(color, 0.0, 1.0), vec3(1.0 / 2.2));
  fragColor = vec4(color, 1.0);
}
"""

  /**
   * The procedural studio painted into the environment texture once at renderer start-up.
   *
   * It is an equirectangular map. The important part is not the exact colour of the backdrop but the
   * structure: a bright overhead soft-box, two coloured side strips and a dark checkered floor. Those
   * shapes are what a polished metal reflects instead of a white dot, and a moving normal makes them slide
   * across the plate.
   */
  const val ENVIRONMENT = """#version 300 es
precision highp float;
out vec4 fragColor;
uniform vec2 uEnvSize;
const float PI = 3.14159265359;

float strip(float az, float center, float halfWidth, float soft) {
  float delta = atan(sin(az - center), cos(az - center));
  return 1.0 - smoothstep(halfWidth - soft, halfWidth, abs(delta));
}

void main() {
  vec2 uv = gl_FragCoord.xy / uEnvSize;
  float phi = (uv.x - 0.5) * 2.0 * PI;
  float theta = uv.y * PI;
  float st = sin(theta);
  // Inverse of the sampling mapping in the metal shader: u = atan(z, x) / 2pi + 0.5,
  // v = acos(y) / pi. Keeping the two in step is what puts a panel where the plate reflects it.
  vec3 d = vec3(st * cos(phi), cos(theta), st * sin(phi));
  float el = asin(clamp(d.y, -1.0, 1.0));
  float az = atan(d.z, d.x);

  vec3 sky = mix(vec3(0.055, 0.065, 0.085), vec3(0.20, 0.23, 0.28), smoothstep(0.0, 1.25, el));
  vec3 floorColor = mix(vec3(0.025, 0.028, 0.035), vec3(0.075, 0.080, 0.095),
                        smoothstep(-1.2, 0.0, el));
  vec3 c = mix(floorColor, sky, smoothstep(-0.03, 0.035, el));
  // A dim floor bounce: bevels that lean downwards reflect this instead of going to pure black.
  c += vec3(0.50, 0.55, 0.66) * (1.0 - smoothstep(0.10, 0.55, abs(el + 0.42))) * 0.09;

  float horizon = el / 0.055;
  c += vec3(0.90, 0.95, 1.05) * exp(-horizon * horizon) * 0.22;

  // A dark studio with a bright band at the horizon. reflect(-V, +Z) is +Z, so this is the band a
  // head-on plate reflects; the rest of the plate is dark, which is what makes the bevels and their
  // bright edges legible. A wall of white would flatten all of that.
  c += vec3(1.0, 0.98, 0.94) * strip(az, 1.57, 0.38, 0.08) *
       (1.0 - smoothstep(0.24, 0.42, abs(el - 0.02))) * 2.0;
  // Coloured side panels: two bevels leaning opposite ways pick up different temperatures.
  c += vec3(0.42, 0.68, 1.0) * strip(az, 2.35, 0.20, 0.05) *
       (1.0 - smoothstep(0.30, 0.48, abs(el - 0.06))) * 1.8;
  c += vec3(1.0, 0.46, 0.16) * strip(az, 0.78, 0.18, 0.05) *
       (1.0 - smoothstep(0.32, 0.50, abs(el - 0.02))) * 1.6;
  // The front key soft-box and a broad overhead panel.
  c += vec3(1.0, 0.95, 0.86) * strip(az, -0.62, 0.40, 0.10) *
       (1.0 - smoothstep(0.20, 0.36, abs(el - 0.60))) * 2.2;
  c += vec3(0.95, 0.98, 1.05) * (1.0 - smoothstep(0.12, 0.32, abs(el - 1.12))) * 1.2;

  // Narrow vertical strip lights standing in the dark around the main band. These are the highlights
  // that travel across a bevel and that a rough surface visibly softens.
  c += vec3(1.0, 0.98, 0.94) * strip(az, 1.18, 0.028, 0.010) *
       (1.0 - smoothstep(0.45, 0.68, abs(el - 0.05))) * 3.4;
  c += vec3(0.80, 0.90, 1.0) * strip(az, 1.98, 0.030, 0.011) *
       (1.0 - smoothstep(0.45, 0.68, abs(el - 0.05))) * 3.0;
  c += vec3(1.0, 0.98, 0.94) * strip(az, 2.55, 0.020, 0.008) *
       (1.0 - smoothstep(0.40, 0.62, abs(el - 0.10))) * 2.4;

  // Dark floor with a sparse grid: downward bevels reflect this, upward ones reflect the soft-boxes.
  float checker = step(0.5, fract(az / PI * 4.0)) * step(0.5, fract((el + 1.6) * 3.0));
  float grid = (1.0 - smoothstep(0.0, 0.02, abs(fract(az / PI * 8.0) - 0.5))) +
               (1.0 - smoothstep(0.0, 0.02, abs(fract((el + 1.6) * 6.0) - 0.5)));
  if (el < -0.04) {
    c = mix(c, c + (checker - 0.35) * 0.035, 1.0);
    c += vec3(0.35, 0.40, 0.48) * grid * 0.04;
  }

  fragColor = vec4(c, 1.0);
}
"""

  /** Hammersley/GGX split-sum BRDF integration, generated once into a small LUT. */
  const val BRDF = """#version 300 es
precision highp float;
out vec4 fragColor;
uniform float uLutSize;
const float PI = 3.14159265359;

float radicalInverseVdC(uint bits) {
  bits = (bits << 16u) | (bits >> 16u);
  bits = ((bits & 0x55555555u) << 1u) | ((bits & 0xAAAAAAAAu) >> 1u);
  bits = ((bits & 0x33333333u) << 2u) | ((bits & 0xCCCCCCCCu) >> 2u);
  bits = ((bits & 0x0F0F0F0Fu) << 4u) | ((bits & 0xF0F0F0F0u) >> 4u);
  bits = ((bits & 0x00FF00FFu) << 8u) | ((bits & 0xFF00FF00u) >> 8u);
  return float(bits) * 2.3283064365386963e-10;
}

vec2 hammersley(uint i, uint n) {
  return vec2(float(i) / float(n), radicalInverseVdC(i));
}

vec3 importanceSampleGGX(vec2 xi, vec3 n, float roughness) {
  float a = roughness * roughness;
  float phi = 2.0 * PI * xi.x;
  float cosTheta = sqrt((1.0 - xi.y) / (1.0 + (a * a - 1.0) * xi.y));
  float sinTheta = sqrt(max(0.0, 1.0 - cosTheta * cosTheta));
  vec3 h = vec3(cos(phi) * sinTheta, sin(phi) * sinTheta, cosTheta);
  vec3 up = abs(n.z) < 0.999 ? vec3(0.0, 0.0, 1.0) : vec3(1.0, 0.0, 0.0);
  vec3 tx = normalize(cross(up, n));
  vec3 ty = cross(n, tx);
  return normalize(tx * h.x + ty * h.y + n * h.z);
}

float geometrySmith(float nDotV, float nDotL, float roughness) {
  float k = roughness * roughness * 0.5;
  return (nDotV / (nDotV * (1.0 - k) + k)) * (nDotL / (nDotL * (1.0 - k) + k));
}

void main() {
  float nDotV = clamp(gl_FragCoord.x / uLutSize, 0.001, 0.999);
  float roughness = clamp(gl_FragCoord.y / uLutSize, 0.02, 1.0);
  vec3 v = vec3(sqrt(1.0 - nDotV * nDotV), 0.0, nDotV);
  vec3 n = vec3(0.0, 0.0, 1.0);
  float a = 0.0;
  float b = 0.0;
  const uint samples = 64u;
  for (uint i = 0u; i < samples; i++) {
    vec2 xi = hammersley(i, samples);
    vec3 h = importanceSampleGGX(xi, n, roughness);
    vec3 l = normalize(2.0 * dot(v, h) * h - v);
    float nDotL = max(l.z, 0.0);
    float nDotH = max(h.z, 0.0);
    float vDotH = max(dot(v, h), 0.0);
    if (nDotL > 0.0) {
      float g = geometrySmith(nDotV, nDotL, roughness);
      float visibility = (g * vDotH) / max(nDotH * nDotV, 0.0001);
      float fc = pow(1.0 - vDotH, 5.0);
      a += (1.0 - fc) * visibility;
      b += fc * visibility;
    }
  }
  fragColor = vec4(a / float(samples), b / float(samples), 0.0, 1.0);
}
"""
}
