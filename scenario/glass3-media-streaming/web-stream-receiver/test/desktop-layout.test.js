import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const stylesheetUrl = new URL('../public/styles.css', import.meta.url);

test('desktop layout keeps the complete receiver dashboard in one viewport', async () => {
  const css = await readFile(stylesheetUrl, 'utf8');

  assert.match(css, /@media \(min-width: 1000px\) and \(min-height: 600px\)/);
  assert.match(css, /grid-template-areas:\s*"hero hero"\s*"viewer toolbar"\s*"viewer connection"\s*"viewer metrics"\s*"viewer events"/);
  assert.match(css, /\.viewer-card\s*\{\s*grid-area:\s*viewer;/);
  assert.match(css, /\.metrics\s*\{\s*grid-area:\s*metrics;/);
  assert.match(css, /#eventLog\s*\{\s*max-height:\s*72px;/);
});
