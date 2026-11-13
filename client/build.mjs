// Bundles the Kaye web app (Firebase JS SDK + app code) into web/assets/kaye.js.
import { build } from 'esbuild';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
await build({
  entryPoints: [join(here, 'src/app.js')], outfile: join(here, '../web/assets/kaye.js'),
  bundle: true, minify: true, format: 'esm', target: ['chrome120', 'safari16'], legalComments: 'none', logLevel: 'warning',
});
console.log('built web/assets/kaye.js');
