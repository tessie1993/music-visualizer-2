// Reads the uniform names a Kotlin scene actually uploads, straight out of
// its source.
//
// This is the whole reason to trust anything this tool renders. A preview
// harness that invents its own uniform values is a drawing of a shader, not a
// preview of a style: a uniform the harness forgets defaults to zero in GL,
// and a zeroed uniform is indistinguishable from a feature that is switched
// off. `uHasMelt = 0` is precisely that failure, and it is the difference
// between a whole medium being there and not.
//
// So the tool does a three-way audit per scene:
//
//   A. what the SHADER declares          (glsl.parseUniforms)
//   B. what the KOTLIN uploads           (here)
//   C. what the HARNESS supplies         (lib/scenes.mjs)
//
// A \ C is fatal - something would silently be zero.
// B \ C is fatal - the harness has drifted behind the app.
// C \ B is fatal - the harness is inventing inputs the app never sends.
// A \ B is reported as a shader-side finding (the app never sets it either).

import fs from 'node:fs';

/**
 * Uniform names uploaded by a Kotlin scene.
 *
 * Covers the three call shapes in this codebase:
 *   loc("uName")                              - fluid scenes
 *   glGetUniformLocation(program, "uName")    - ShaderScene's samplers
 *   setUniform1f("uName", ...)                - ShaderScene's scalars
 */
export function extractUploadedUniforms(kotlinPath) {
  const src = fs.readFileSync(kotlinPath, 'utf8');
  const names = new Set();
  const patterns = [
    /\bloc\(\s*"(u\w+)"\s*\)/g,
    /glGetUniformLocation\(\s*\w+\s*,\s*"(u\w+)"\s*\)/g,
    /\bsetUniform1f\(\s*"(u\w+)"/g,
    /\bcLoc\(\s*"(u\w+)"\s*\)/g,
  ];
  for (const re of patterns) {
    let m;
    while ((m = re.exec(src)) !== null) names.add(m[1]);
  }
  return names;
}

/**
 * The three-way audit. Returns `{ errors, notes }`; a non-empty `errors`
 * means the render would not be trustworthy and the caller should refuse to
 * present it as one.
 */
export function auditUniforms({ sceneId, declared, uploaded, supplied, ignoreUploaded = [] }) {
  const errors = [];
  const notes = [];
  const declaredNames = new Set(declared.map((d) => d.name));
  const ignore = new Set(ignoreUploaded);

  for (const d of declared) {
    if (!supplied.has(d.name)) {
      errors.push(
        `[${sceneId}] shader declares '${d.name}' (${d.type}${d.isArray ? `[${d.length}]` : ''}) ` +
        `but the harness sets no value - it would render as zero`,
      );
    }
  }
  for (const name of uploaded) {
    if (ignore.has(name)) continue;
    if (!supplied.has(name)) {
      errors.push(
        `[${sceneId}] Kotlin uploads '${name}' but the harness does not - the harness has drifted behind the app`,
      );
    }
  }
  for (const name of supplied) {
    if (!uploaded.has(name) && !ignore.has(name)) {
      errors.push(
        `[${sceneId}] the harness supplies '${name}' but no Kotlin upload of it was found - invented input`,
      );
    }
  }
  for (const name of uploaded) {
    if (!declaredNames.has(name) && !ignore.has(name)) {
      notes.push(`[${sceneId}] Kotlin uploads '${name}' but this shader does not declare it (no-op, location -1)`);
    }
  }
  for (const d of declared) {
    if (!uploaded.has(d.name)) {
      notes.push(`[${sceneId}] shader declares '${d.name}' but no Kotlin upload was found - APP-SIDE zero`);
    }
  }
  return { errors, notes };
}
