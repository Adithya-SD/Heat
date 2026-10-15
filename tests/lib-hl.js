/* Loads the Heat Link code out of public/index.html (the single source of truth) so Node tests can run it:
   the session protocol (HL) and the H.264 helpers (AVC). Neither touches the DOM. */
const fs = require('fs'), path = require('path'), vm = require('vm');

function loadAll(file = path.join(__dirname, '..', 'public', 'index.html')) {
  const src = fs.readFileSync(file, 'utf8');
  const a = src.indexOf('/* ===== link:protocol:begin ===== */'), b = src.indexOf('/* ===== link:media:end ===== */');
  if (a < 0 || b < 0) throw new Error('link markers not found in ' + file);
  const ctx = vm.createContext({
    crypto: require('crypto').webcrypto, TextEncoder, TextDecoder, setInterval, clearInterval, setTimeout, clearTimeout,
    console, Date, Promise, Uint8Array, Map, Math, Array, Error, Object, String, Number, JSON
  });
  return vm.runInContext(src.slice(a, b) + '\n({ HL, AVC });', ctx, { filename: 'index.html#link' });
}

const loadHL = file => loadAll(file).HL;
module.exports = { loadHL, loadAll };
