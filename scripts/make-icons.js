'use strict';

// Regenerate app icons from build/icon.svg.
// Usage: npm run icons
const fs = require('fs');
const path = require('path');
const { Resvg } = require('@resvg/resvg-js');
const png2icons = require('png2icons');

const buildDir = path.join(__dirname, '..', 'build');
const svgPath = path.join(buildDir, 'icon.svg');

function renderPng(svg, size) {
  const resvg = new Resvg(svg, {
    fitTo: { mode: 'width', value: size },
    font: { loadSystemFonts: true },
    background: 'rgba(0,0,0,0)',
  });
  return resvg.render().asPng();
}

function main() {
  const svg = fs.readFileSync(svgPath, 'utf8');

  // Master raster used as the source for .ico/.icns.
  const png1024 = renderPng(svg, 1024);
  fs.writeFileSync(path.join(buildDir, 'icon.png'), png1024);

  // Windows .ico (multi-size) and macOS .icns from the 1024 master.
  const ico = png2icons.createICO(png1024, png2icons.BILINEAR, 0, false);
  fs.writeFileSync(path.join(buildDir, 'icon.ico'), ico);

  const icns = png2icons.createICNS(png1024, png2icons.BILINEAR, 0);
  fs.writeFileSync(path.join(buildDir, 'icon.icns'), icns);

  // A 256px copy that ships inside the app bundle for the runtime window icon
  // (build/ is build-time only and isn't packaged).
  const assetsDir = path.join(__dirname, '..', 'src', 'renderer', 'assets');
  fs.mkdirSync(assetsDir, { recursive: true });
  fs.writeFileSync(path.join(assetsDir, 'icon.png'), renderPng(svg, 256));

  for (const f of ['icon.png', 'icon.ico', 'icon.icns']) {
    const s = fs.statSync(path.join(buildDir, f)).size;
    console.log(`build/${f}  ${(s / 1024).toFixed(1)} KB`);
  }
  console.log('src/renderer/assets/icon.png  (window icon)');
}

main();
