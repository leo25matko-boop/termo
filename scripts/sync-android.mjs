import {cp, mkdir, rm, stat} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import path from 'node:path';

const projectRoot = fileURLToPath(new URL('../', import.meta.url));
const assetsRoot = path.join(projectRoot, 'android', 'app', 'src', 'main', 'assets');
const publicRoot = path.join(projectRoot, 'public');
const browserModules = ['controller.mjs', 'upstream.mjs', 'parsers.mjs', 'charts.mjs'];

// Only these two generated directories are replaced. Native source and resources
// remain outside this copy operation; Node backends and credentials stay out of APKs.
for (const name of ['public', 'lib']) {
  const target = path.resolve(assetsRoot, name);
  if (path.dirname(target) !== path.resolve(assetsRoot)) {
    throw new Error('Invalid Android assets destination.');
  }
  await rm(target, {recursive: true, force: true});
  await mkdir(target, {recursive: true});
}

await cp(publicRoot, path.join(assetsRoot, 'public'), {recursive: true});
for (const name of browserModules) {
  const source = path.join(projectRoot, 'lib', name);
  if (!(await stat(source)).isFile()) throw new Error(`Missing browser module: ${name}`);
  await cp(source, path.join(assetsRoot, 'lib', name));
}

console.log('Android assets synchronized from public/ and browser-safe parser modules.');
