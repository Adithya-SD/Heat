/* Loads the Heat Link protocol out of public/index.html (the single source of truth) so Node tests can run it. */
const fs = require('fs'), path = require('path'), vm = require('vm');

function loadHL(file = path.join(__dirname, '..', 'public', 'index.html')) {
  const src = fs.readFileSync(file, 'utf8');
  const a = src.indexOf('/* ===== link:protocol:begin ===== */'), b = src.indexOf('/* ===== link:protocol:end ===== */');
  if (a < 0 || b < 0) throw new Error('link protocol markers not found in ' + file);
  const ctx = vm.createContext({
    crypto: require('crypto').webcrypto, TextEncoder, TextDecoder, setInterval, clearInterval, setTimeout, clearTimeout,
    console, Date, Promise, Uint8Array, Map, Math, Array, Error, Object, String, Number, JSON
  });
  return vm.runInContext(src.slice(a, b) + '\nHL;', ctx, { filename: 'index.html#link-protocol' });
}
module.exports = { loadHL };
