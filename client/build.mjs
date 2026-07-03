// Builds the IEA client Java agent jar (no `jar` tool / Gradle required).
//  - downloads ASM + LWJGL into client/libs (once)
//  - compiles src with `javac --release 8`
//  - shades ASM classes into the output
//  - packages build/iea-agent.jar with a Premain-Class manifest
//
// Usage: node client/build.mjs   (or npm run client:build from the root)
import { createRequire } from 'module';
import { spawnSync } from 'child_process';
import { fileURLToPath } from 'url';
import https from 'https';
import fs from 'fs';
import path from 'path';

const require = createRequire(import.meta.url);
const AdmZip = require('adm-zip'); // resolved from the launcher's node_modules
const __dirname = path.dirname(fileURLToPath(import.meta.url));

const ASM = '9.7';
const DEPS = [
  ['asm', `https://repo1.maven.org/maven2/org/ow2/asm/asm/${ASM}/asm-${ASM}.jar`],
  ['asm-tree', `https://repo1.maven.org/maven2/org/ow2/asm/asm-tree/${ASM}/asm-tree-${ASM}.jar`],
  ['asm-analysis', `https://repo1.maven.org/maven2/org/ow2/asm/asm-analysis/${ASM}/asm-analysis-${ASM}.jar`],
  ['asm-commons', `https://repo1.maven.org/maven2/org/ow2/asm/asm-commons/${ASM}/asm-commons-${ASM}.jar`],
  ['lwjgl', 'https://libraries.minecraft.net/org/lwjgl/lwjgl/lwjgl/2.9.4-nightly-20150209/lwjgl-2.9.4-nightly-20150209.jar'],
];

const libsDir = path.join(__dirname, 'libs');
const classesDir = path.join(__dirname, 'build', 'classes');
const outJar = path.join(__dirname, 'build', 'iea-agent.jar');

function download(url, dest, redirects = 0) {
  return new Promise((resolve, reject) => {
    https.get(url, (res) => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        if (redirects > 5) return reject(new Error('too many redirects'));
        res.resume();
        return resolve(download(new URL(res.headers.location, url).toString(), dest, redirects + 1));
      }
      if (res.statusCode !== 200) { res.resume(); return reject(new Error(`HTTP ${res.statusCode} for ${url}`)); }
      const out = fs.createWriteStream(dest);
      res.pipe(out);
      out.on('finish', () => out.close(resolve));
      out.on('error', reject);
    }).on('error', reject);
  });
}

function walkJava(dir, acc = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, e.name);
    if (e.isDirectory()) walkJava(full, acc);
    else if (e.name.endsWith('.java')) acc.push(full);
  }
  return acc;
}

async function main() {
  fs.mkdirSync(libsDir, { recursive: true });
  fs.rmSync(classesDir, { recursive: true, force: true });
  fs.mkdirSync(classesDir, { recursive: true });

  // 1. dependencies
  const jars = [];
  for (const [name, url] of DEPS) {
    const dest = path.join(libsDir, `${name}.jar`);
    if (!fs.existsSync(dest)) {
      process.stdout.write(`downloading ${name}… `);
      await download(url, dest);
      console.log('ok');
    }
    jars.push(dest);
  }

  // 2. compile
  const sources = walkJava(path.join(__dirname, 'src'));
  const cp = jars.join(path.delimiter);
  console.log(`compiling ${sources.length} sources (--release 8)…`);
  const javac = spawnSync('javac', ['--release', '8', '-encoding', 'UTF-8', '-cp', cp, '-d', classesDir, ...sources],
    { stdio: 'inherit' });
  if (javac.status !== 0) { console.error('javac failed'); process.exit(1); }

  // 3. shade ASM classes into the output (so the agent is self-contained)
  for (const [name, ] of DEPS) {
    if (!name.startsWith('asm')) continue;
    const zip = new AdmZip(path.join(libsDir, `${name}.jar`));
    for (const entry of zip.getEntries()) {
      const n = entry.entryName;
      if (entry.isDirectory) continue;
      if (!n.startsWith('org/objectweb/asm/') || !n.endsWith('.class')) continue;
      if (n.endsWith('module-info.class')) continue;
      const out = path.join(classesDir, n);
      fs.mkdirSync(path.dirname(out), { recursive: true });
      fs.writeFileSync(out, entry.getData());
    }
  }

  // 4. package the agent jar
  const manifest =
    'Manifest-Version: 1.0\n' +
    'Premain-Class: dev.iea.agent.Agent\n' +
    'Agent-Class: dev.iea.agent.Agent\n' +
    'Can-Retransform-Classes: true\n';
  const zip = new AdmZip();
  zip.addLocalFolder(classesDir);
  zip.addFile('META-INF/MANIFEST.MF', Buffer.from(manifest, 'utf8'));
  zip.writeZip(outJar);

  console.log(`built ${path.relative(path.join(__dirname, '..'), outJar)}  ${(fs.statSync(outJar).size / 1024).toFixed(1)} KB`);
}

main().catch((e) => { console.error(e); process.exit(1); });
