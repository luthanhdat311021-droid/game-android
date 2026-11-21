'use strict';
const LEVELS = { debug: 10, info: 20, warn: 30, error: 40, silent: 99 };
const level = () => LEVELS[process.env.LOG_LEVEL || 'info'] || 20;
const out = (lv, args) => {
  if (LEVELS[lv] < level()) return;
  const ts = new Date().toISOString().slice(11, 23);
  (lv === 'error' ? console.error : console.log)(`[${ts}] ${lv.toUpperCase()}`, ...args);
};
module.exports = {
  debug: (...a) => out('debug', a),
  info: (...a) => out('info', a),
  warn: (...a) => out('warn', a),
  error: (...a) => out('error', a)
};
